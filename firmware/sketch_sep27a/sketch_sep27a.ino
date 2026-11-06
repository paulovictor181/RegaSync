#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <ArduinoMqttClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <esp_system.h>
#include <time.h>
#include "device_config.h"
#include "flow_calibration.h"
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
};

RemoteConfig settings = {0, 5000, 38, 45, true};
Preferences preferences;
QueueHandle_t telemetryQueue;
QueueHandle_t configQueue;
QueueHandle_t stateQueue;
WiFiClient plainTransport;
WiFiClientSecure secureTransport;
MqttClient mqtt(MQTT_USE_TLS ? static_cast<Client&>(secureTransport)
                           : static_cast<Client&>(plainTransport));
portMUX_TYPE pulseMux = portMUX_INITIALIZER_UNLOCKED;
volatile uint32_t pulseCounter = 0;
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

void IRAM_ATTR countPulse() {
  portENTER_CRITICAL_ISR(&pulseMux);
  pulseCounter++;
  if (calibrationCounting) calibrationPulseCounter++;
  portEXIT_CRITICAL_ISR(&pulseMux);
}

void printCalibrationHelp() {
  Serial.println("Comandos (115200 baud, enviar com Nova linha):");
  Serial.println("  CALIBRAR_FLUXO   - zera contagem e LIGA o rele para encher o copo");
  Serial.println("  FINALIZAR_FLUXO  - ao atingir 500 ml, DESLIGA e salva a calibracao");
  Serial.println("  CANCELAR_FLUXO   - DESLIGA e descarta a coleta");
  Serial.println("  STATUS_FLUXO     - mostra fator e estado da calibracao");
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
  } else if (command == "CALIBRAR_FLUXO") {
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
void receiveConfig(int messageSize) {
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

void networkTask(void*) {
  if (String(WIFI_SSID).startsWith("PREENCHA") || String(MQTT_HOST).startsWith("PREENCHA") ||
      String(MQTT_PASSWORD).startsWith("PREENCHA") || strlen(MQTT_USER) == 0) {
    Serial.println("Rede desativada: preencha secrets.h antes de enviar dados.");
    vTaskDelete(nullptr);
  }
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
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
  uint32_t lastWifiAttempt = millis();
  uint32_t lastMqttAttempt = millis() - 5000;
  uint32_t lastStateSent = 0;
  uint32_t acknowledgedVersion = UINT32_MAX;
  int acknowledgedCalibrationStatus = -1;
  bool acknowledgedPause = false;

  for (;;) {
    uint32_t now = millis();
    if (WiFi.status() != WL_CONNECTED) {
      mqtt.stop();
      if (now - lastWifiAttempt >= 10000) {
        lastWifiAttempt = now;
        WiFi.reconnect();
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    // TLS e timestamps precisam de um relogio sincronizado. A rega e independente.
    if (time(nullptr) < 1735689600) {
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    if (!mqtt.connected()) {
      if (now - lastMqttAttempt < 5000) {
        vTaskDelay(pdMS_TO_TICKS(50));
        continue;
      }
      lastMqttAttempt = now;
      if (!mqtt.connect(MQTT_HOST, MQTT_PORT) || !mqtt.subscribe(configTopic, 1)) {
        Serial.printf("MQTT indisponivel (codigo %d); nova tentativa em 5 s.\n", mqtt.connectError());
        mqtt.stop();
        continue;
      }
      Serial.println("MQTT conectado; assinatura de configuracao ativa.");
      acknowledgedVersion = UINT32_MAX;
      acknowledgedCalibrationStatus = -1;
      moistureSent = false;  // Reenvio usa o mesmo id, deduplicado pelo servidor.
    }
    mqtt.poll();
    if (!mqtt.connected()) continue;

    DeviceState state;
    if (xQueuePeek(stateQueue, &state, 0) == pdTRUE &&
        (acknowledgedVersion != state.config.version ||
         acknowledgedCalibrationStatus != static_cast<int>(state.calibrationStatus) ||
         acknowledgedPause != state.irrigationPaused || now - lastStateSent >= 10000)) {
      if (!sendState(state)) {
        mqtt.stop();
        continue;
      }
      acknowledgedVersion = state.config.version;
      acknowledgedCalibrationStatus = static_cast<int>(state.calibrationStatus);
      acknowledgedPause = state.irrigationPaused;
      lastStateSent = now;
    }

    if (!hasPending && xQueueReceive(telemetryQueue, &pending, 0) == pdTRUE) {
      hasPending = true;
      moistureSent = false;
    }
    if (hasPending) {
      if (!moistureSent) moistureSent = sendReading(pending, true);
      if (!moistureSent || !sendReading(pending, false)) {
        mqtt.stop();
      } else {
        hasPending = false;
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
  bootNumber = preferences.getULong64("boot", 0) + 1;
  preferences.putULong64("boot", bootNumber);
  char randomId[17];
  snprintf(randomId, sizeof(randomId), "%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random());
  bootId = String(MQTT_USER) + ":" + String(static_cast<unsigned long long>(bootNumber)) + ":" + randomId;
  configTopic = String("/regasync/dispositivos/") + MQTT_USER + "/config";
  stateTopic = String("/regasync/dispositivos/") + MQTT_USER + "/estado";
  moistureTopic = String("/umidade/") + NUMERO_SENSOR_UMIDADE;
  flowTopic = String("/fluxo/") + NUMERO_SENSOR_FLUXO;
  telemetryQueue = xQueueCreate(30, sizeof(Telemetry));
  configQueue = xQueueCreate(1, sizeof(RemoteConfig));
  stateQueue = xQueueCreate(1, sizeof(DeviceState));
  if (!telemetryQueue || !configQueue || !stateQueue) {
    Serial.println("Memoria insuficiente; rele permanece desligado.");
    for (;;) delay(1000);
  }
  if (xTaskCreate(networkTask, "mqtt-network", 12288, nullptr, 1, nullptr) != pdPASS) {
    Serial.println("Falha ao iniciar tarefa MQTT. Controle local permanece ativo.");
  }
  previousMeasurement = previousPublication = millis();
  Serial.println("RegaSync iniciado: controle local + telemetria/configuracao MQTT.");
  Serial.printf("Fator de fluxo carregado: %.4f pulsos/litro.\n", pulsesPerLiter);
  printCalibrationHelp();
  if (irrigationPaused) Serial.println("Rega pausada por calibracao anterior. Envie RETOMAR_REGA quando estiver pronto.");
}

void loop() {
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
  if (!flowCalibration.active() && (irrigationPaused || !settings.automaticWatering) && relayOn) {
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
  int humidity = constrain(static_cast<int>(100.0 * (ADC_SECO - adc) / (ADC_SECO - ADC_MOLHADO)), 0, 100);
  if (!flowCalibration.active()) {
    relayOn = !irrigationPaused && settings.automaticWatering && humidity < (relayOn ? settings.humidityOff : settings.humidityOn);
  }
  digitalWrite(PINO_RELE, relayOn ? RELE_LIGADO : RELE_DESLIGADO);
  DeviceState state = {settings, humidity, adc, relayOn, flow, totalVolume,
                       pulsesPerLiter, flowCalibration.status(), calibrationPulses(), irrigationPaused};
  xQueueOverwrite(stateQueue, &state);

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
