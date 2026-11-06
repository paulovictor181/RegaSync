package br.com.ufersa.regasync.irrigation.service;

import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation.Status;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.SensorRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ManualIrrigationService {
    public record View(Long id, String status, String label, BigDecimal liters, long seconds,
                       boolean canStart, boolean canFinish, String message, boolean interrupted,
                       Instant feedbackAt, Instant startedAt, Instant completedAt) { }
    public record Command(String topic, String payload) { }
    private final ManualIrrigationRepository sessions;
    private final SensorRepository sensors;
    private final ObjectMapper mapper;
    private final String device;
    public ManualIrrigationService(ManualIrrigationRepository sessions, SensorRepository sensors,
                                  ObjectMapper mapper, @Value("${app.mqtt.device-username:esp32}") String device) {
        this.sessions = sessions; this.sensors = sensors; this.mapper = mapper; this.device = device;
        if (!device.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("MQTT_ESP_USERNAME deve conter apenas letras, números, _ ou -.");
    }
    private String user() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private boolean admin() { return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); }
    public Sensor flowSensor(long id) {
        Sensor sensor = sensors.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (sensor.getType() != SensorType.FLUXO) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rega manual exige um sensor de fluxo.");
        return sensor;
    }
    @Transactional @PreAuthorize("isAuthenticated()")
    public long start(long sensorId) {
        Sensor sensor = flowSensor(sensorId);
        if (!sensor.isActive()) throw new IllegalArgumentException("Ative o sensor de fluxo antes de iniciar.");
        if (sessions.findFirstByDeviceUsernameAndStatusNotOrderByIdDesc(device, Status.COMPLETED).isPresent()) {
            throw new IllegalArgumentException("Já existe uma rega manual em andamento neste ESP32.");
        }
        return sessions.saveAndFlush(new ManualIrrigation(sensor, user(), device)).getId();
    }
    @Transactional @PreAuthorize("isAuthenticated()")
    public void finish(long sensorId, long sessionId) {
        ManualIrrigation session = sessions.lockById(sessionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (session.getSensor().getId() != sensorId) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!session.getOwnerEmail().equals(user()) && !admin()) throw new AccessDeniedException("Somente quem iniciou ou um administrador pode finalizar.");
        session.requestStop();
    }
    @Transactional(readOnly = true) @PreAuthorize("isAuthenticated()")
    public View view(long sensorId) {
        Sensor sensor = flowSensor(sensorId);
        var active = sessions.findFirstByDeviceUsernameAndStatusNotOrderByIdDesc(device, Status.COMPLETED);
        if (active.isPresent() && active.get().getSensor().getId() != sensorId) {
            return new View(null, "BUSY", "ESP32 ocupado", BigDecimal.ZERO, 0, false, false,
                    "Finalize a sessão do outro sensor antes de iniciar uma nova rega.", false, null, null, null);
        }
        var session = active.or(() -> sessions.findFirstBySensorIdAndOwnerEmailOrderByIdDesc(sensorId, user()));
        if (session.isEmpty()) return new View(null, "NONE", "Pronto para iniciar", BigDecimal.ZERO, 0,
                sensor.isActive(), false, "Inicie e aguarde a confirmação do ESP32 antes de abrir a válvula manual.", false, null, null, null);
        return toView(session.get(), sensor.isActive());
    }
    private View toView(ManualIrrigation m, boolean sensorActive) {
        String label = switch (m.getStatus()) {
            case STARTING -> "Aguardando pausa do automático";
            case RUNNING -> m.isInterrupted() ? "Medição interrompida" : "Rega manual em andamento";
            case STOPPING -> "Finalizando medição";
            case RESUMING -> "Restaurando controle automático";
            case COMPLETED -> "Rega manual concluída";
        };
        String message = switch (m.getStatus()) {
            case STARTING -> "Ainda não abra a válvula: o ESP32 precisa confirmar que desligou o relé automático.";
            case RUNNING -> "Automático pausado. Pode abrir o ramal manual. Feche a válvula antes de finalizar.";
            case STOPPING -> "Mantenha a válvula manual fechada. Aguardando a contagem final do ESP32.";
            case RESUMING -> "Volume registrado. Aguardando confirmação de retomada do controle automático.";
            case COMPLETED -> "Controle automático liberado conforme a configuração anterior do ESP32.";
        };
        if (m.getError() != null) message = m.getError();
        if (m.getStatus() == Status.RUNNING && (m.getFeedbackAt() == null || m.getFeedbackAt().isBefore(Instant.now().minusSeconds(30)))) {
            label = "ESP32 sem confirmação recente";
            message = "Mantenha o ramal manual fechado enquanto a conexão é restabelecida. A pausa permanece salva no ESP32.";
        }
        boolean owner = m.getOwnerEmail().equals(user()) || admin();
        long seconds = m.getStartedAt() == null ? 0 : Math.max(0, Duration.between(m.getStartedAt(),
                m.getMeasuredEndAt() == null ? Instant.now() : m.getMeasuredEndAt()).getSeconds());
        return new View(m.getId(), m.getStatus().name(), label, m.getLiters(), seconds,
                m.getStatus() == Status.COMPLETED && sensorActive,
                owner && (m.getStatus() == Status.STARTING || m.getStatus() == Status.RUNNING),
                message, m.isInterrupted(), m.getFeedbackAt(), m.getStartedAt(), m.getCompletedAt());
    }
    @Transactional(readOnly = true) @PreAuthorize("isAuthenticated()")
    public Page<View> history(long sensorId, int page) {
        flowSensor(sensorId);
        Pageable pageable = PageRequest.of(Math.max(0, page), 10, Sort.by("id").descending());
        var history = admin() ? sessions.findBySensorId(sensorId, pageable) : sessions.findBySensorIdAndOwnerEmail(sensorId, user(), pageable);
        return history.map(m -> toView(m, false));
    }
    @Transactional(readOnly = true)
    public List<Command> pendingCommands() {
        return sessions.findByStatusNot(Status.COMPLETED).stream().map(m -> {
            String action = switch (m.getStatus()) { case STOPPING -> "medir_fim"; case RESUMING -> "retomar"; default -> "pausar"; };
            String payload = mapper.writeValueAsString(Map.of("sessaoId", m.getId(), "versao", m.commandVersion(),
                    "sensorNumero", m.getSensorNumber(), "acao", action));
            return new Command("/regasync/dispositivos/" + m.getDeviceUsername() + "/rega-manual/comando", payload);
        }).toList();
    }
    @Transactional
    public void acknowledge(String topic, String payload) {
        try {
            var json = mapper.readTree(payload);
            if (!json.path("sessaoId").isIntegralNumber() || !json.path("sessaoId").canConvertToLong() ||
                !json.path("versao").isIntegralNumber() || !json.path("versao").canConvertToLong()) return;
            var found = sessions.lockById(json.path("sessaoId").asLong());
            if (found.isEmpty()) return;
            var m = found.get();
            if (m.getStatus() == Status.COMPLETED || !topic.equals("/regasync/dispositivos/" + m.getDeviceUsername() + "/rega-manual/estado") ||
                json.path("versao").asLong() != m.commandVersion()) return;
            if (json.hasNonNull("erro")) { m.recordError(json.path("erro").asString().substring(0, Math.min(240, json.path("erro").asString().length()))); return; }
            String phase = json.path("fase").asString("");
            boolean release = m.getStatus() == Status.RESUMING;
            if (!(release ? phase.equals("liberada") : (m.getStatus() == Status.STOPPING ? phase.equals("medida") : phase.equals("pausada")))) return;
            if (!json.path("automaticoPausado").isBoolean() || json.path("automaticoPausado").asBoolean() == release) return;
            if (!release && (!json.path("releLigado").isBoolean() || json.path("releLigado").asBoolean())) return;
            if (!json.path("sensorNumero").isIntegralNumber() || !json.path("sensorNumero").canConvertToInt() || json.path("sensorNumero").asInt() != m.getSensorNumber()) return;
            if (!json.path("pulsosInicio").isIntegralNumber() || !json.path("pulsosAtuais").isIntegralNumber() ||
                !json.path("pulsosInicio").canConvertToLong() || !json.path("pulsosAtuais").canConvertToLong() || !json.path("interrompida").isBoolean()) return;
            long initial = json.path("pulsosInicio").asLong(), current = json.path("pulsosAtuais").asLong();
            String boot = json.path("bootId").asString("");
            Instant measured = Instant.parse(json.path("medidoEm").asString());
            if (initial < 0 || current < 0 || boot.isBlank() || boot.length() > 128 || measured.isAfter(Instant.now().plusSeconds(30)) ||
                measured.isBefore(m.getCreatedAt().minusSeconds(300))) return;
            m.acknowledge(phase, initial, current, boot, json.path("interrompida").asBoolean(), measured);
        } catch (IllegalArgumentException | DateTimeException | tools.jackson.core.JacksonException ignored) {
            // Payload invalido nao altera a sessao nem libera o automatico.
        }
    }
}
