package br.com.ufersa.regasync.sensor.service;

import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.SensorRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class HumidityCalibrationService {
    public record Command(String topic, String payload) { }
    public record View(int adcSeco, int adcMolhado, int versao, boolean confirmada, Instant confirmadaEm) { }
    private final SensorRepository sensors;
    private final ObjectMapper mapper;
    private final String device;
    public HumidityCalibrationService(SensorRepository sensors, ObjectMapper mapper,
                                      @Value("${app.mqtt.device-username:esp32}") String device) {
        this.sensors = sensors; this.mapper = mapper; this.device = device;
    }
    @Transactional(readOnly = true)
    public List<Command> desiredCalibrations() {
        return sensors.findByType(SensorType.UMIDADE).stream().map(sensor -> new Command(
                "/regasync/dispositivos/"+device+"/sensores/umidade/"+sensor.getNumber()+"/calibracao",
                mapper.writeValueAsString(Map.of("sensorNumero", sensor.getNumber(), "versao", sensor.getCalibrationVersion(),
                        "adcSeco", sensor.getDryAdc(), "adcMolhado", sensor.getWetAdc())))).toList();
    }
    @Transactional(readOnly = true)
    public View view(long sensorId) {
        Sensor s = sensors.findById(sensorId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (s.getType() != SensorType.UMIDADE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sensor deve ser de umidade.");
        return new View(s.getDryAdc(), s.getWetAdc(), s.getCalibrationVersion(), s.isHumidityCalibrationConfirmed(), s.getHumidityAppliedAt());
    }
    @Transactional
    public void acknowledge(String topic, String payload) {
        if (!topic.equals("/regasync/dispositivos/"+device+"/estado")) return;
        try {
            var json = mapper.readTree(payload);
            for (String field : List.of("sensorUmidadeNumero", "versaoCalibracaoUmidade", "adcSeco", "adcMolhado")) {
                if (!json.path(field).isIntegralNumber() || !json.path(field).canConvertToInt()) return;
            }
            if (!json.path("online").asBoolean(false)) return;
            var found = sensors.lockByTypeAndNumber(SensorType.UMIDADE, json.path("sensorUmidadeNumero").asInt());
            if (found.isEmpty()) return;
            Sensor s = found.get();
            if (json.path("adcSeco").asInt() != s.getDryAdc() || json.path("adcMolhado").asInt() != s.getWetAdc()) return;
            Instant at = Instant.parse(json.path("medidoEm").asString());
            if (at.isAfter(Instant.now().plusSeconds(30))) return;
            s.confirmHumidityCalibration(json.path("versaoCalibracaoUmidade").asInt(), at);
        } catch (IllegalArgumentException | DateTimeException | tools.jackson.core.JacksonException ignored) {
            // Confirmacao malformada nao muda a calibracao do cadastro.
        }
    }
}
