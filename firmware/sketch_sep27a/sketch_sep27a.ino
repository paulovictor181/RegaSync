#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <ArduinoMqttClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <esp_system.h>
#include <time.h>
#include <atomic>
#include "device_config.h"
#include "flow_calibration.h"
#include "manual_irrigation.h"
#include "humidity_calibration.h"
#include "trusted_roots.h"
#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

// MQTT/Wi-Fi rodam em outra tarefa; a rega local continua mesmo sem internet.
struct RemoteConfig {
  uint32_t version;
  uint32_t publishIntervalMs;
  uint8_t humidityOn;
  uint8_t humidityOff;
  bool automaticWatering;
};

struct Telemetry {
  uint64_t sequence;
  uint64_t pulses;
  uint32_t intervalMs;
  int adc;
  time_t measuredAt;
};

struct DeviceState {
  RemoteConfig config;
  int humidity;
  int adc;
  bool relayOn;
  double flow;
  double volume;
  double pulsesPerLiter;
  FlowCalibrationStatus calibrationStatus;
  uint32_t calibrationPulses;
  bool irrigationPaused;
  HumidityCalibration humidityCalibration;
};

struct NetworkStatus {
  char phase[64];
  char localIp[16];
  bool configured;
  bool wifiConnected;
  bool clockSynced;
  bool mqttConnected;
  bool hasPublished;
  int wifiStatus;
  int rssi;
  int lastMqttError;
  uint16_t lastWifiReason;
  uint32_t lastPublicationMs;
  uint32_t updatedMs;
};

RemoteConfig settings = {0, 5000, 38, 45, true};
Preferences preferences;
QueueHandle_t telemetryQueue;
QueueHandle_t configQueue;
QueueHandle_t stateQueue;
QueueHandle_t manualCommandQueue;
QueueHandle_t manualStateQueue;
QueueHandle_t networkStatusQueue;
QueueHandle_t humidityCalibrationQueue;
HumidityCalibration humidityCalibration = {0, ADC_SECO, ADC_MOLHADO};
NetworkStatus networkStatus = {};
std::atomic<uint16_t> lastWifiReason{0};
ManualRecord manualRecord = {};
bool manualHold = false;
uint32_t manualPauseAt = 0;
WiFiClient plainTransport;
WiFiClientSecure secureTransport;
MqttClient mqtt(MQTT_USE_TLS ? static_cast<Client&>(secureTransport)
                           : static_cast<Client&>(plainTransport));
portMUX_TYPE pulseMux = portMUX_INITIALIZER_UNLOCKED;
volatile uint32_t pulseCounter = 0;
volatile uint64_t totalPulseCounter = 0;
volatile uint32_t calibrationPulseCounter = 0;
volatile bool calibrationCounting = false;
FlowCalibration flowCalibration;
double pulsesPerLiter = PULSOS_POR_LITRO;
bool irrigationPaused = false;
bool relayOn = false;
uint32_t previousMeasurement = 0;
uint32_t previousPublication = 0;
uint64_t publicationPulses = 0;
uint64_t sequenceNumber = 0;
uint64_t bootNumber = 0;
double totalVolume = 0;
String bootId;
String configTopic;
String stateTopic;
String moistureTopic;
String flowTopic;
String manualCommandTopic;
String manualStateTopic;
String humidityCalibrationTopic;

// Executado somente pela tarefa de rede. O Serial consulta uma copia via fila.
void updateNetworkStatus(const char* phase) {
  if (strcmp(networkStatus.phase, phase) != 0) Serial.printf("[REDE] %s\n", phase);
  strlcpy(networkStatus.phase, phase, sizeof(networkStatus.phase));
  networkStatus.wifiStatus = WiFi.status();
  networkStatus.wifiConnected = networkStatus.wifiStatus == WL_CONNECTED;
  networkStatus.clockSynced = time(nullptr) >= 1735689600;
  networkStatus.mqttConnected = networkStatus.wifiConnected && mqtt.connected();
  networkStatus.lastWifiReason = lastWifiReason.load();
  networkStatus.rssi = networkStatus.wifiConnected ? WiFi.RSSI() : 0;
  String ip = networkStatus.wifiConnected ? WiFi.localIP().toString() : "sem IP";
  strlcpy(networkStatus.localIp, ip.c_str(), sizeof(networkStatus.localIp));
  networkStatus.updatedMs = millis();
  xQueueOverwrite(networkStatusQueue, &networkStatus);
}

void printNetworkStatus() {
  NetworkStatus status;
  if (xQueuePeek(networkStatusQueue, &status, 0) != pdTRUE) {
    Serial.println("[REDE] Tarefa de rede ainda nao iniciou.");
    return;
  }
  Serial.printf("[REDE] Etapa: %s (atualizado ha %lu ms)\n", status.phase, (unsigned long)(millis() - status.updatedMs));
  Serial.printf("[REDE] Credenciais preenchidas: %s\n", status.configured ? "SIM" : "NAO; confira secrets.h");
  Serial.printf("[REDE] Wi-Fi: %s | status=%d | ultimo motivo de desconexao=%u\n",
                status.wifiConnected ? "CONECTADO" : "DESCONECTADO", status.wifiStatus, status.lastWifiReason);
  if (status.wifiConnected) Serial.printf("[REDE] IP local: %s | sinal: %d dBm\n", status.localIp, status.rssi);
  Serial.printf("[REDE] Relogio UTC/NTP: %s\n", status.clockSynced ? "SINCRONIZADO" : "AGUARDANDO");
  Serial.printf("[REDE] MQTT: %s | ultimo erro=%d | TLS=%s | porta=%u\n", status.mqttConnected ? "CONECTADO" : "DESCONECTADO", status.lastMqttError, MQTT_USE_TLS ? "SIM" : "NAO", MQTT_PORT);
  if (status.hasPublished) Serial.printf("[REDE] Ultima leitura confirmada pelo broker ha %lu segundos.\n", (unsigned long)((millis() - status.lastPublicationMs) / 1000));
  else Serial.println("[REDE] Nenhuma leitura confirmada pelo broker desde o boot.");
  if (!status.wifiConnected) Serial.println("[REDE] Confira SSID (maiusculas/minusculas), senha e rede 2,4 GHz.");
  else if (!status.clockSynced) Serial.println("[REDE] Wi-Fi conectado, aguardando NTP; TLS e leituras precisam de horario UTC.");
  else if (!status.mqttConnected) Serial.println("[REDE] Confira broker, porta 8883, certificado TLS e credenciais MQTT do ESP.");
}

void IRAM_ATTR countPulse() {
  portENTER_CRITICAL_ISR(&pulseMux);
  pulseCounter++;
  totalPulseCounter++;
  if (calibrationCounting) calibrationPulseCounter++;
  portEXIT_CRITICAL_ISR(&pulseMux);
}

uint64_t totalPulses() {
  portENTER_CRITICAL(&pulseMux);
  uint64_t result = totalPulseCounter;
  portEXIT_CRITICAL(&pulseMux);
  return result;
}

void updateManualSnapshot(const char* error = "") {
  ManualSnapshot snapshot = {};
  snapshot.record = manualRecord;
  snapshot.interrupted = manualRecord.phase != ManualPhase::Settling &&
      manualRecord.session != 0 && strcmp(manualRecord.boot, bootId.c_str()) != 0;
  snapshot.currentPulses = manualRecord.phase == ManualPhase::Paused && !snapshot.interrupted
      ? totalPulses() : manualRecord.finalPulses;
  snapshot.paused = manualHold;
  snapshot.relayOn = relayOn;
  strlcpy(snapshot.error, error, sizeof(snapshot.error));
  xQueueOverwrite(manualStateQueue, &snapshot);
}

bool saveManualRecord(const ManualRecord& record) {
  if (preferences.putBytes("manual", &record, sizeof(record)) != sizeof(record)) {
    // Sem confirmacao de gravacao, nao liberar a pausa em RAM.
    manualHold = true;
    relayOn = false;
    digitalWrite(PINO_RELE, RELE_DESLIGADO);
    Serial.println("Falha ao salvar estado da rega manual; automatico permanece pausado.");
    return false;
  }
  manualRecord = record;
  return true;
}

void processManualCommand(const ManualCommand& command) {
  if (command.sensor != NUMERO_SENSOR_FLUXO || command.version < manualRecord.version) return;
  if (command.version == manualRecord.version) { updateManualSnapshot(); return; }
  if (manualHold && manualRecord.phase != ManualPhase::Released && manualRecord.session != 0 && manualRecord.session != command.session) return;
  ManualRecord next = manualRecord;
  next.magic = MANUAL_RECORD_MAGIC;
  next.session = command.session;
  next.version = command.version;
  next.sensor = command.sensor;
  if (command.action == ManualAction::Pause) {
    if (flowCalibration.cancel()) stopCalibrationPump();
    manualHold = true;
    relayOn = false;
    digitalWrite(PINO_RELE, RELE_DESLIGADO);
    next.phase = ManualPhase::Settling;
    next.initialPulses = next.finalPulses = 0;
    bootId.toCharArray(next.boot, sizeof(next.boot));
    if (saveManualRecord(next)) {
      manualPauseAt = millis();
      Serial.println("Site solicitou rega manual: automatico pausado; aguardando estabilizacao do fluxo.");
    }
  } else if (command.action == ManualAction::Finish) {
    manualHold = true;
    relayOn = false;
    digitalWrite(PINO_RELE, RELE_DESLIGADO);
    if (manualRecord.session != command.session || manualRecord.phase == ManualPhase::Settling || manualRecord.session == 0) {
      // Cancelamento antes da confirmacao de inicio: nenhum volume manual autorizado.
      next.initialPulses = totalPulses();
      bootId.toCharArray(next.boot, sizeof(next.boot));
    }
    next.finalPulses = strcmp(next.boot, bootId.c_str()) == 0 ? totalPulses() : next.initialPulses;
    next.phase = ManualPhase::Measured;
    if (saveManualRecord(next)) Serial.println("Contagem manual encerrada; aguardando liberacao do site.");
  } else {
    next.phase = ManualPhase::Released;
    if (saveManualRecord(next)) {
      manualHold = false;
      Serial.println("Site liberou a pausa manual; controle anterior de umidade restaurado.");
    }
  }
  updateManualSnapshot();
}

void settleManualPause() {
  if (manualRecord.phase != ManualPhase::Settling || millis() - manualPauseAt < MANUAL_SETTLE_MS) return;
  ManualRecord next = manualRecord;
  next.initialPulses = next.finalPulses = totalPulses();
  next.phase = ManualPhase::Paused;
  bootId.toCharArray(next.boot, sizeof(next.boot));
  if (saveManualRecord(next)) {
    Serial.println("Pausa confirmada: medicao manual pronta no site.");
    updateManualSnapshot();
  }
}

void receiveManualCommand(int messageSize) {
  if (messageSize <= 0 || messageSize > 512) { while (mqtt.available()) mqtt.read(); return; }
  String payload;
  while (mqtt.available()) payload += static_cast<char>(mqtt.read());
  JsonDocument doc;
  if (deserializeJson(doc, payload) || !doc["sessaoId"].is<uint64_t>() ||
      !doc["versao"].is<uint64_t>() || !doc["sensorNumero"].is<uint32_t>() || !doc["acao"].is<const char*>()) return;
  ManualCommand command = {};
  command.session = doc["sessaoId"];
  command.version = doc["versao"];
  command.sensor = doc["sensorNumero"];
  if (command.session == 0 || command.version == 0 || command.version > 9007199254740991ULL) return;
  String action = doc["acao"].as<String>();
  if (action == "pausar") command.action = ManualAction::Pause;
  else if (action == "medir_fim") command.action = ManualAction::Finish;
  else if (action == "retomar") command.action = ManualAction::Resume;
  else return;
  if (command.session > 3002399751580330ULL) return;
  uint64_t expected = command.session * 3 - (command.action == ManualAction::Pause ? 2 : command.action == ManualAction::Finish ? 1 : 0);
  if (command.version != expected) return;
  xQueueOverwrite(manualCommandQueue, &command);
}

void printCalibrationHelp() {
  Serial.println("Comandos (115200 baud, enviar com Nova linha):");
  Serial.println("  CALIBRAR_FLUXO   - zera contagem e LIGA o rele para encher o copo");
  Serial.println("  FINALIZAR_FLUXO  - ao atingir 500 ml, DESLIGA e salva a calibracao");
  Serial.println("  CANCELAR_FLUXO   - DESLIGA e descarta a coleta");
  Serial.println("  STATUS_FLUXO     - mostra fator e estado da calibracao");
  Serial.println("  STATUS_REDE      - mostra Wi-Fi, UTC, MQTT e ultimo envio confirmado");
  Serial.println("  STATUS_UMIDADE   - mostra faixa ADC e versao recebida do site");
  Serial.println("  RETOMAR_REGA     - libera novamente a rega automatica configurada");
  Serial.println("  AJUDA            - mostra estes comandos");
}

uint32_t calibrationPulses() {
  portENTER_CRITICAL(&pulseMux);
  uint32_t pulses = calibrationPulseCounter;
  portEXIT_CRITICAL(&pulseMux);
  return pulses;
}

uint32_t stopCalibrationPump() {
  digitalWrite(PINO_RELE, RELE_DESLIGADO);
  relayOn = false;
  portENTER_CRITICAL(&pulseMux);
  calibrationCounting = false;
  uint32_t pulses = calibrationPulseCounter;
  portEXIT_CRITICAL(&pulseMux);
  // Nao religar automaticamente assim que a coleta terminar.
  irrigationPaused = true;
  return pulses;
}

void handleSerialCommand(String command) {
  command.trim();
  command.toUpperCase();
  if (command == "AJUDA") {
    printCalibrationHelp();
  } else if (command == "STATUS_REDE") {
    printNetworkStatus();
  } else if (command == "STATUS_UMIDADE") {
    Serial.printf("[UMIDADE] Seco=%d | Molhado=%d | Calibracao=v%lu | GPIO=%d\n",
                  humidityCalibration.dryAdc, humidityCalibration.wetAdc, (unsigned long)humidityCalibration.version, PINO_UMIDADE);
    DeviceState latest;
    if (xQueuePeek(stateQueue, &latest, 0) == pdTRUE) Serial.printf("[UMIDADE] Ultimo ADC=%d | Umidade=%d%%\n", latest.adc, latest.humidity);
  } else if (command == "CALIBRAR_FLUXO") {
    if (manualHold) { Serial.println("Rega manual ativa no site. Finalize a sessao antes de calibrar."); return; }
    if (flowCalibration.active()) {
      Serial.println("Ja existe uma coleta ativa. Finalize ou cancele primeiro.");
      return;
    }
    if (preferences.putBool("calPause", true) != sizeof(bool)) {
      Serial.println("Falha ao salvar pausa de rega; calibracao nao iniciada.");
      return;
    }
    flowCalibration.start(millis());
    irrigationPaused = true;
    portENTER_CRITICAL(&pulseMux);
    calibrationPulseCounter = 0;
    calibrationCounting = true;
    portEXIT_CRITICAL(&pulseMux);
    relayOn = true;
    digitalWrite(PINO_RELE, RELE_LIGADO);
    Serial.println("Coleta iniciada: RELE LIGADO. Ao atingir 500 ml envie FINALIZAR_FLUXO.");
    Serial.println("O volume e confirmado por voce, pela marca do copo. Limite: 120 segundos.");
  } else if (command == "FINALIZAR_FLUXO") {
    if (!flowCalibration.active()) {
      Serial.println("Nao ha coleta ativa. Envie CALIBRAR_FLUXO para iniciar.");
      return;
    }
    uint32_t pulses = stopCalibrationPump();
    if (flowCalibration.finish(millis(), pulses)) {
      double factor = flowCalibration.factor();
      if (preferences.putDouble("flowPpl", factor) == sizeof(double)) {
        pulsesPerLiter = factor;
        Serial.printf("Calibracao salva: %lu pulsos em 500 ml = %.4f pulsos/litro.\n", (unsigned long)pulses, factor);
        Serial.println("Atualize Pulsos por litro no cadastro do sensor de fluxo no RegaSync com esse valor.");
      } else {
        flowCalibration.storageFailed();
        Serial.println("Falha ao salvar. Fator anterior mantido.");
      }
    } else {
      Serial.printf("Calibracao descartada: %s. Fator anterior mantido.\n", FlowCalibration::label(flowCalibration.status()));
    }
    Serial.println("RELE DESLIGADO. Envie RETOMAR_REGA quando estiver pronto.");
  } else if (command == "CANCELAR_FLUXO") {
    if (flowCalibration.cancel()) {
      stopCalibrationPump();
      Serial.println("Coleta cancelada; fator anterior mantido. RELE DESLIGADO.");
      Serial.println("Envie RETOMAR_REGA quando estiver pronto.");
    } else {
      Serial.println("Nao ha coleta ativa para cancelar.");
    }
  } else if (command == "RETOMAR_REGA") {
    if (manualHold) { Serial.println("A pausa da rega manual so pode ser liberada pelo site."); return; }
    if (flowCalibration.active()) {
      Serial.println("Finalize ou cancele a coleta antes de retomar a rega.");
      return;
    }
    if (preferences.putBool("calPause", false) != sizeof(bool)) {
      Serial.println("Falha ao salvar retomada; rega continua pausada.");
      return;
    }
    irrigationPaused = false;
    Serial.println(settings.automaticWatering ? "Rega automatica liberada." : "Rega automatica desabilitada na configuracao MQTT.");
  } else if (command == "STATUS_FLUXO") {
    Serial.printf("Fator: %.4f pulsos/litro | Calibracao: %s | Pulsos: %lu | Rega pausada: %s\n",
                  pulsesPerLiter, FlowCalibration::label(flowCalibration.status()),
                  (unsigned long)calibrationPulses(), irrigationPaused ? "SIM" : "NAO");
  } else {
    Serial.println("Comando desconhecido. Envie AJUDA.");
  }
}

void pollSerialCommands() {
  static String line;
  static bool overflow = false;
  // Limita o trabalho por ciclo para continuar atendendo o controle e o timeout.
  for (int i = 0; i < 64 && Serial.available(); ++i) {
    char ch = static_cast<char>(Serial.read());
    if (ch == '\n' || ch == '\r') {
      if (overflow) Serial.println("Comando muito longo. Envie AJUDA.");
      else if (!line.isEmpty()) handleSerialCommand(line);
      line = "";
      overflow = false;
    } else if (line.length() < 63 && !overflow) {
      line += ch;
    } else {
      overflow = true;
    }
  }
}

bool validConfig(const RemoteConfig& config) {
  return config.version <= 2147483647UL && config.humidityOn < config.humidityOff &&
         config.humidityOff <= 100 && config.publishIntervalMs >= 1000 &&
         config.publishIntervalMs <= 60000;
}

// Aceita somente snapshots completos. Versoes antigas sao ignoradas no loop.
void receiveHumidityCalibration(int messageSize) {
  if (messageSize <= 0 || messageSize > 512) { while (mqtt.available()) mqtt.read(); return; }
  String payload;
  while (mqtt.available()) payload += static_cast<char>(mqtt.read());
  JsonDocument doc;
  if (deserializeJson(doc, payload) || !doc.is<JsonObject>() || doc.size() != 4 ||
      !doc["versao"].is<uint32_t>() || !doc["sensorNumero"].is<int>() ||
      !doc["adcSeco"].is<int>() || !doc["adcMolhado"].is<int>() || doc["sensorNumero"].as<int>() != NUMERO_SENSOR_UMIDADE) return;
  HumidityCalibration candidate = {doc["versao"].as<uint32_t>(), doc["adcSeco"].as<int>(), doc["adcMolhado"].as<int>()};
  if (candidate.version == 0 || !validHumidityCalibration(candidate)) {
    Serial.println("[UMIDADE] Calibracao rejeitada: valores precisam ser distintos e entre 0 e 4095.");
    return;
  }
  xQueueOverwrite(humidityCalibrationQueue, &candidate);
}

void receiveConfig(int messageSize) {
  if (mqtt.messageTopic() == manualCommandTopic) { receiveManualCommand(messageSize); return; }
  if (mqtt.messageTopic() == humidityCalibrationTopic) { receiveHumidityCalibration(messageSize); return; }
  if (mqtt.messageTopic() != configTopic || messageSize <= 0 || messageSize > 512) {
    while (mqtt.available()) mqtt.read();
    return;
  }
  String payload;
  payload.reserve(messageSize);
  while (mqtt.available()) payload += static_cast<char>(mqtt.read());
  JsonDocument doc;
  if (deserializeJson(doc, payload) || !doc.is<JsonObject>() || doc.size() != 5 ||
      !doc["versao"].is<uint32_t>() || !doc["umidadeLigar"].is<unsigned int>() ||
      !doc["umidadeDesligar"].is<unsigned int>() || !doc["regaAutomatica"].is<bool>() ||
      !doc["intervaloPublicacaoMs"].is<uint32_t>()) {
    Serial.println("Configuracao MQTT rejeitada: JSON invalido ou incompleto.");
    return;
  }
  unsigned int on = doc["umidadeLigar"].as<unsigned int>();
  unsigned int off = doc["umidadeDesligar"].as<unsigned int>();
  if (on >= off || off > 100) {
    Serial.println("Configuracao MQTT rejeitada: limites de umidade invalidos.");
    return;
  }
  RemoteConfig candidate = {};
  candidate.version = doc["versao"];
  candidate.publishIntervalMs = doc["intervaloPublicacaoMs"];
  candidate.humidityOn = on;
  candidate.humidityOff = off;
  candidate.automaticWatering = doc["regaAutomatica"];
  if (!validConfig(candidate) || candidate.version == 0) {
    Serial.println("Configuracao MQTT rejeitada: versao ou intervalo invalido.");
    return;
  }
  xQueueOverwrite(configQueue, &candidate);
}

bool sendJson(const String& topic, const JsonDocument& document, bool retained) {
  String payload;
  serializeJson(document, payload);
  if (!mqtt.beginMessage(topic, static_cast<unsigned long>(payload.length()), retained, 1)) return false;
  mqtt.print(payload);
  return mqtt.endMessage() == 1;
}

String isoTime(time_t value) {
  struct tm utc;
  gmtime_r(&value, &utc);
  char text[25];
  strftime(text, sizeof(text), "%Y-%m-%dT%H:%M:%SZ", &utc);
  return String(text);
}

bool sendReading(const Telemetry& reading, bool moisture) {
  JsonDocument document;
  document["id"] = bootId + ":" + String(static_cast<unsigned long long>(reading.sequence));
  document["medidoEm"] = isoTime(reading.measuredAt);
  if (moisture) {
    document["adc"] = reading.adc;
  } else {
    document["pulsos"] = reading.pulses;
    document["intervaloMs"] = reading.intervalMs;
  }
  return sendJson(moisture ? moistureTopic : flowTopic, document, false);
}

bool sendState(const DeviceState& state) {
  JsonDocument document;
  document["online"] = true;
  document["versao"] = state.config.version;
  document["umidadeLigar"] = state.config.humidityOn;
  document["umidadeDesligar"] = state.config.humidityOff;
  document["regaAutomatica"] = state.config.automaticWatering;
  document["intervaloPublicacaoMs"] = state.config.publishIntervalMs;
  document["releLigado"] = state.relayOn;
  document["umidade"] = state.humidity;
  document["adc"] = state.adc;
  document["sensorUmidadeNumero"] = NUMERO_SENSOR_UMIDADE;
  document["versaoCalibracaoUmidade"] = state.humidityCalibration.version;
  document["adcSeco"] = state.humidityCalibration.dryAdc;
  document["adcMolhado"] = state.humidityCalibration.wetAdc;
  document["vazaoLmin"] = state.flow;
  document["volumeDesdeBootLitros"] = state.volume;
  document["pulsosPorLitro"] = state.pulsesPerLiter;
  document["calibracaoFluxo"] = FlowCalibration::label(state.calibrationStatus);
  document["pulsosCalibracao"] = state.calibrationPulses;
  document["volumeReferenciaMl"] = FlowCalibration::targetMl;
  document["regaPausada"] = state.irrigationPaused;
  document["medidoEm"] = isoTime(time(nullptr));
  return sendJson(stateTopic, document, true);
}

bool sendManualState(const ManualSnapshot& state) {
  if (state.record.session == 0) return true;
  JsonDocument doc;
  doc["sessaoId"] = state.record.session;
  doc["versao"] = state.record.version;
  doc["sensorNumero"] = state.record.sensor;
  const char* phase = state.record.phase == ManualPhase::Paused ? "pausada" :
      state.record.phase == ManualPhase::Measured ? "medida" :
      state.record.phase == ManualPhase::Settling ? "pausando" : "liberada";
  doc["fase"] = phase;
  doc["automaticoPausado"] = state.paused;
  doc["releLigado"] = state.relayOn;
  doc["pulsosInicio"] = state.record.initialPulses;
  doc["pulsosAtuais"] = state.currentPulses;
  doc["bootId"] = state.record.boot;
  doc["interrompida"] = state.interrupted;
  doc["medidoEm"] = isoTime(time(nullptr));
  if (state.error[0]) doc["erro"] = state.error;
  return sendJson(manualStateTopic, doc, true);
}

void networkTask(void*) {
  if (String(WIFI_SSID).startsWith("PREENCHA") || String(MQTT_HOST).startsWith("PREENCHA") ||
      String(WIFI_PASSWORD).startsWith("PREENCHA") || String(MQTT_PASSWORD).startsWith("PREENCHA") ||
      strlen(WIFI_SSID) == 0 || strlen(MQTT_HOST) == 0 || strlen(MQTT_USER) == 0) {
    updateNetworkStatus("Rede desativada: preencha secrets.h");
    vTaskDelete(nullptr);
  }
  networkStatus.configured = true;
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
      lastWifiReason.store(info.wifi_sta_disconnected.reason);
      Serial.printf("[REDE] Wi-Fi desconectado; motivo=%u.\n", info.wifi_sta_disconnected.reason);
    } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
      Serial.println("[REDE] Wi-Fi conectado e endereco IP recebido.");
    }
  });
  updateNetworkStatus("Conectando ao Wi-Fi");
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  secureTransport.setCACert(TRUSTED_ROOTS);
  secureTransport.setHandshakeTimeout(5);
  mqtt.setId(String("regasync-") + MQTT_USER);
  mqtt.setUsernamePassword(MQTT_USER, MQTT_PASSWORD);
  mqtt.setConnectionTimeout(5000);
  mqtt.setKeepAliveInterval(30000);
  mqtt.setCleanSession(true);
  mqtt.onMessage(receiveConfig);
  mqtt.beginWill(stateTopic, true, 1);
  mqtt.print("{\"online\":false}");
  mqtt.endWill();

  Telemetry pending = {};
  bool hasPending = false;
  bool moistureSent = false;
  uint32_t lastNetworkLog = millis() - 10000;
  uint32_t lastMqttAttempt = millis() - 5000;
  uint32_t lastStateSent = 0;
  uint32_t acknowledgedVersion = UINT32_MAX;
  uint32_t acknowledgedHumidityVersion = UINT32_MAX;
  uint64_t acknowledgedManualVersion = UINT64_MAX;
  int acknowledgedManualPhase = -1;
  uint32_t lastManualSent = 0;
  int acknowledgedCalibrationStatus = -1;
  bool acknowledgedPause = false;
  bool acknowledgedRelayOn = false;

  for (;;) {
    uint32_t now = millis();
    if (WiFi.status() != WL_CONNECTED) {
      mqtt.stop();
      updateNetworkStatus("Aguardando conexao Wi-Fi");
      if (now - lastNetworkLog >= 10000) {
        lastNetworkLog = now;
        Serial.printf("[REDE] Wi-Fi ainda sem conexao: status=%d, motivo=%u. Confira SSID/senha/rede 2,4 GHz.\n", networkStatus.wifiStatus, networkStatus.lastWifiReason);
      }
      // A biblioteca cuida da reconexao. Nao sobrepor WiFi.reconnect() a uma tentativa ativa.
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    // TLS e timestamps precisam de um relogio sincronizado. A rega e independente.
    if (time(nullptr) < 1735689600) {
      updateNetworkStatus("Wi-Fi conectado; aguardando relogio UTC/NTP");
      if (now - lastNetworkLog >= 10000) {
        lastNetworkLog = now;
        Serial.println("[REDE] Aguardando NTP. Confira se a rede permite DNS e NTP (UDP 123).");
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    if (!mqtt.connected()) {
      updateNetworkStatus("Wi-Fi e UTC prontos; aguardando conexao MQTT");
      if (now - lastMqttAttempt < 5000) {
        vTaskDelay(pdMS_TO_TICKS(50));
        continue;
      }
      lastMqttAttempt = now;
      updateNetworkStatus("Conectando ao broker MQTT");
      if (!mqtt.connect(MQTT_HOST, MQTT_PORT)) {
        networkStatus.lastMqttError = mqtt.connectError();
        Serial.printf("MQTT indisponivel (codigo %d); nova tentativa em 5 s.\n", mqtt.connectError());
        if (MQTT_USE_TLS) {
          char error[128];
          int code = secureTransport.lastError(error, sizeof(error));
          if (code != 0) Serial.printf("[REDE] TLS: erro %d: %s\n", code, error);
        }
        mqtt.stop();
        updateNetworkStatus("Falha na conexao MQTT; tentando novamente");
        continue;
      }
      if (!mqtt.subscribe(configTopic, 1) || !mqtt.subscribe(manualCommandTopic, 1) || !mqtt.subscribe(humidityCalibrationTopic, 1)) {
        networkStatus.lastMqttError = -10;
        Serial.println("[REDE] Falha ao assinar topicos MQTT; confira as ACLs do Mosquitto.");
        mqtt.stop();
        updateNetworkStatus("Falha na assinatura dos topicos MQTT");
        continue;
      }
      networkStatus.lastMqttError = 0;
      Serial.println("MQTT conectado; assinatura de configuracao ativa.");
      acknowledgedVersion = UINT32_MAX;
      acknowledgedHumidityVersion = UINT32_MAX;
      acknowledgedManualVersion = UINT64_MAX;
      acknowledgedCalibrationStatus = -1;
      moistureSent = false;  // Reenvio usa o mesmo id, deduplicado pelo servidor.
    }
    mqtt.poll();
    if (!mqtt.connected()) { updateNetworkStatus("Conexao MQTT interrompida"); continue; }
    updateNetworkStatus("Wi-Fi, UTC e MQTT conectados");

    ManualSnapshot manual;
    if (xQueuePeek(manualStateQueue, &manual, 0) == pdTRUE && manual.record.session != 0 &&
        (acknowledgedManualVersion != manual.record.version || acknowledgedManualPhase != static_cast<int>(manual.record.phase) || now - lastManualSent >= 2000)) {
      if (!sendManualState(manual)) { mqtt.stop(); continue; }
      acknowledgedManualVersion = manual.record.version;
      acknowledgedManualPhase = static_cast<int>(manual.record.phase);
      lastManualSent = now;
    }

    DeviceState state;
    if (xQueuePeek(stateQueue, &state, 0) == pdTRUE &&
        (acknowledgedVersion != state.config.version ||
         acknowledgedHumidityVersion != state.humidityCalibration.version ||
         acknowledgedCalibrationStatus != static_cast<int>(state.calibrationStatus) ||
         acknowledgedPause != state.irrigationPaused || acknowledgedRelayOn != state.relayOn || now - lastStateSent >= 10000)) {
      if (!sendState(state)) {
        mqtt.stop();
        continue;
      }
      acknowledgedVersion = state.config.version;
      acknowledgedHumidityVersion = state.humidityCalibration.version;
      acknowledgedCalibrationStatus = static_cast<int>(state.calibrationStatus);
      acknowledgedPause = state.irrigationPaused;
      acknowledgedRelayOn = state.relayOn;
      lastStateSent = now;
    }

    if (!hasPending && xQueueReceive(telemetryQueue, &pending, 0) == pdTRUE) {
      hasPending = true;
      moistureSent = false;
    }
    if (hasPending) {
      if (!moistureSent) moistureSent = sendReading(pending, true);
      if (!moistureSent || !sendReading(pending, false)) {
        networkStatus.lastMqttError = -11;
        Serial.println("[REDE] Publicacao das leituras nao confirmada; sera repetida apos reconectar.");
        mqtt.stop();
        updateNetworkStatus("Falha ao confirmar publicacao MQTT");
      } else {
        hasPending = false;
        networkStatus.hasPublished = true;
        networkStatus.lastPublicationMs = millis();
        networkStatus.lastMqttError = 0;
        Serial.println("[REDE] Leituras de umidade e fluxo confirmadas pelo broker (QoS 1).");
        updateNetworkStatus("Wi-Fi, UTC e MQTT conectados");
      }
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}

void setup() {
  Serial.begin(115200);
  digitalWrite(PINO_RELE, RELE_DESLIGADO);
  pinMode(PINO_RELE, OUTPUT);
  analogReadResolution(12);
  pinMode(PINO_FLUXO, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PINO_FLUXO), countPulse, FALLING);

  if (!preferences.begin("regasync", false)) {
    Serial.println("Falha ao abrir NVS; rele permanece desligado.");
    for (;;) delay(1000);
  }
  if (preferences.getBytesLength("config") == sizeof(settings)) {
    RemoteConfig stored;
    preferences.getBytes("config", &stored, sizeof(stored));
    if (validConfig(stored)) settings = stored;
  }
  double storedFactor = preferences.getDouble("flowPpl", PULSOS_POR_LITRO);
  if (FlowCalibration::validFactor(storedFactor)) pulsesPerLiter = storedFactor;
  irrigationPaused = preferences.getBool("calPause", false);
  if (preferences.getBytesLength("humCal") == sizeof(humidityCalibration)) {
    HumidityCalibration stored;
    preferences.getBytes("humCal", &stored, sizeof(stored));
    if (validHumidityCalibration(stored)) humidityCalibration = stored;
  }
  bootNumber = preferences.getULong64("boot", 0) + 1;
  preferences.putULong64("boot", bootNumber);
  char randomId[17];
  snprintf(randomId, sizeof(randomId), "%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random());
  bootId = String(MQTT_USER) + ":" + String(static_cast<unsigned long long>(bootNumber)) + ":" + randomId;
  configTopic = String("/regasync/dispositivos/") + MQTT_USER + "/config";
  stateTopic = String("/regasync/dispositivos/") + MQTT_USER + "/estado";
  manualCommandTopic = String("/regasync/dispositivos/") + MQTT_USER + "/rega-manual/comando";
  manualStateTopic = String("/regasync/dispositivos/") + MQTT_USER + "/rega-manual/estado";
  humidityCalibrationTopic = String("/regasync/dispositivos/") + MQTT_USER + "/sensores/umidade/" + NUMERO_SENSOR_UMIDADE + "/calibracao";
  moistureTopic = String("/umidade/") + NUMERO_SENSOR_UMIDADE;
  flowTopic = String("/fluxo/") + NUMERO_SENSOR_FLUXO;
  telemetryQueue = xQueueCreate(30, sizeof(Telemetry));
  configQueue = xQueueCreate(1, sizeof(RemoteConfig));
  stateQueue = xQueueCreate(1, sizeof(DeviceState));
  manualCommandQueue = xQueueCreate(1, sizeof(ManualCommand));
  manualStateQueue = xQueueCreate(1, sizeof(ManualSnapshot));
  networkStatusQueue = xQueueCreate(1, sizeof(NetworkStatus));
  humidityCalibrationQueue = xQueueCreate(1, sizeof(HumidityCalibration));
  if (!telemetryQueue || !configQueue || !stateQueue || !manualCommandQueue || !manualStateQueue || !networkStatusQueue || !humidityCalibrationQueue) {
    Serial.println("Memoria insuficiente; rele permanece desligado.");
    for (;;) delay(1000);
  }
  if (preferences.getBytesLength("manual") == sizeof(manualRecord)) {
    ManualRecord stored;
    preferences.getBytes("manual", &stored, sizeof(stored));
    if (stored.magic == MANUAL_RECORD_MAGIC && static_cast<uint8_t>(stored.phase) <= 3 && stored.boot[127] == '\0') {
      manualRecord = stored;
      manualHold = stored.session != 0 && stored.phase != ManualPhase::Released;
      manualPauseAt = millis();
    }
  }
  updateManualSnapshot();
  if (xTaskCreate(networkTask, "mqtt-network", 12288, nullptr, 1, nullptr) != pdPASS) {
    Serial.println("Falha ao iniciar tarefa MQTT. Controle local permanece ativo.");
  }
  previousMeasurement = previousPublication = millis();
  Serial.println("RegaSync iniciado: controle local + telemetria/configuracao MQTT.");
  Serial.printf("Fator de fluxo carregado: %.4f pulsos/litro.\n", pulsesPerLiter);
  Serial.printf("[UMIDADE] Faixa carregada: seco=%d, molhado=%d, versao=%lu.\n", humidityCalibration.dryAdc, humidityCalibration.wetAdc, (unsigned long)humidityCalibration.version);
  printCalibrationHelp();
  if (irrigationPaused) Serial.println("Rega pausada por calibracao anterior. Envie RETOMAR_REGA quando estiver pronto.");
}

void loop() {
  HumidityCalibration candidateCalibration;
  if (xQueueReceive(humidityCalibrationQueue, &candidateCalibration, 0) == pdTRUE && candidateCalibration.version > humidityCalibration.version) {
    if (preferences.putBytes("humCal", &candidateCalibration, sizeof(candidateCalibration)) == sizeof(candidateCalibration)) {
      humidityCalibration = candidateCalibration;
      Serial.printf("[UMIDADE] Faixa recebida do site e salva: seco=%d, molhado=%d, versao=%lu.\n",
                    humidityCalibration.dryAdc, humidityCalibration.wetAdc, (unsigned long)humidityCalibration.version);
    } else {
      Serial.println("[UMIDADE] Falha ao salvar faixa; mantendo calibracao anterior.");
    }
  }
  ManualCommand command;
  if (xQueueReceive(manualCommandQueue, &command, 0) == pdTRUE) processManualCommand(command);
  settleManualPause();
  pollSerialCommands();
  if (flowCalibration.expire(millis())) {
    stopCalibrationPump();
    Serial.println("Calibracao cancelada por limite de 120 s. RELE DESLIGADO; fator anterior mantido.");
    Serial.println("Envie RETOMAR_REGA quando estiver pronto.");
  }
  RemoteConfig candidate;
  if (xQueueReceive(configQueue, &candidate, 0) == pdTRUE && candidate.version > settings.version) {
    if (preferences.putBytes("config", &candidate, sizeof(candidate)) == sizeof(candidate)) {
      settings = candidate;
      Serial.printf("Configuracao v%lu persistida e aplicada.\n", (unsigned long)settings.version);
      if (!settings.automaticWatering && flowCalibration.cancel()) {
        stopCalibrationPump();
        Serial.println("Calibracao cancelada pelo ajuste regaAutomatica=false. Rele desligado.");
      }
    } else {
      Serial.println("Configuracao rejeitada: falha ao persistir na NVS.");
    }
  }
  if (manualHold || (!flowCalibration.active() && (irrigationPaused || !settings.automaticWatering) && relayOn)) {
    relayOn = false;
    digitalWrite(PINO_RELE, RELE_DESLIGADO);
  }
  uint32_t now = millis();
  if (now - previousMeasurement < 1000) {
    delay(5);
    return;
  }

  portENTER_CRITICAL(&pulseMux);
  uint32_t pulses = pulseCounter;
  pulseCounter = 0;
  uint32_t measuredAtMs = millis();
  portEXIT_CRITICAL(&pulseMux);
  uint32_t interval = measuredAtMs - previousMeasurement;
  previousMeasurement = measuredAtMs;
  publicationPulses += pulses;
  double flow = pulses * 60000.0 / (interval * pulsesPerLiter);
  totalVolume += pulses / pulsesPerLiter;

  long sum = 0;
  for (int i = 0; i < NUM_LEITURAS; ++i) {
    sum += analogRead(PINO_UMIDADE);
    delay(2);
  }
  int adc = sum / NUM_LEITURAS;
  int humidity = humidityPercent(adc, humidityCalibration);
  if (!flowCalibration.active()) {
    relayOn = !manualHold && !irrigationPaused && settings.automaticWatering && humidity < (relayOn ? settings.humidityOff : settings.humidityOn);
  }
  digitalWrite(PINO_RELE, relayOn ? RELE_LIGADO : RELE_DESLIGADO);
  DeviceState state = {settings, humidity, adc, relayOn, flow, totalVolume,
                       pulsesPerLiter, flowCalibration.status(), calibrationPulses(), irrigationPaused, humidityCalibration};
  xQueueOverwrite(stateQueue, &state);
  updateManualSnapshot();

  uint32_t publicationInterval = measuredAtMs - previousPublication;
  if (publicationInterval >= settings.publishIntervalMs) {
    time_t utc = time(nullptr);
    if (utc >= 1735689600) {
      Telemetry reading = {++sequenceNumber, publicationPulses, publicationInterval, adc, utc};
      if (xQueueSend(telemetryQueue, &reading, 0) != pdTRUE) {
        Serial.println("Fila MQTT cheia: amostra descartada; controle local permanece ativo.");
      }
    }
    publicationPulses = 0;
    previousPublication = measuredAtMs;
  }
  Serial.printf("ADC=%d | Umidade=%d%% | Vazao=%.2f L/min | Volume=%.2f L | Rele=%s | Config=v%lu\n",
                adc, humidity, flow, totalVolume, relayOn ? "LIGADO" : "DESLIGADO", (unsigned long)settings.version);
  if (flowCalibration.active()) {
    Serial.printf("Coleta 500 ml: %lu pulsos. Ao atingir a marca envie FINALIZAR_FLUXO.\n", (unsigned long)calibrationPulses());
  }
}
