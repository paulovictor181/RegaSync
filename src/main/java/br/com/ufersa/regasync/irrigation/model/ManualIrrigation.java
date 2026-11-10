package br.com.ufersa.regasync.irrigation.model;

import br.com.ufersa.regasync.sensor.model.Sensor;
import jakarta.persistence.*;
import java.math.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "manual_irrigations")
@Getter
public class ManualIrrigation {
    public enum Status { STARTING, RUNNING, STOPPING, RESUMING, COMPLETED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sensor_id") private Sensor sensor;
    @Column(name = "owner_email", nullable = false, length = 254) private String ownerEmail;
    @Column(name = "device_username", nullable = false, length = 64) private String deviceUsername;
    @Column(name = "sensor_number", nullable = false) private int sensorNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "pulses_per_liter", nullable = false, precision = 12, scale = 4) private BigDecimal pulsesPerLiter;
    @Column(name = "start_pulses") private Long startPulses;
    @Column(name = "last_pulses") private Long lastPulses;
    @Column(name = "boot_id", length = 128) private String bootId;
    @Column(nullable = false, precision = 20, scale = 4) private BigDecimal liters = BigDecimal.ZERO;
    @Column(nullable = false) private boolean interrupted;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "stop_requested_at") private Instant stopRequestedAt;
    @Column(name = "measured_end_at") private Instant measuredEndAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "feedback_at") private Instant feedbackAt;
    @Column(length = 240) private String error;

    protected ManualIrrigation() { }
    public ManualIrrigation(Sensor sensor, String owner, String device) {
        this.sensor = sensor; this.sensorNumber = sensor.getNumber(); this.ownerEmail = owner;
        this.deviceUsername = device; this.pulsesPerLiter = sensor.getPulsesPerLiter();
        this.status = Status.STARTING; this.createdAt = Instant.now();
    }
    public long commandVersion() {
        return Math.addExact(Math.multiplyExact(id, 3L), status == Status.STOPPING ? -1L : status == Status.RESUMING || status == Status.COMPLETED ? 0L : -2L);
    }
    public void requestStop() {
        if (status == Status.STARTING || status == Status.RUNNING) {
            status = Status.STOPPING; stopRequestedAt = Instant.now(); error = null;
        }
    }
    public void recordError(String message) { error = message; feedbackAt = Instant.now(); }
    public void acknowledge(String phase, long initial, long current, String boot, boolean lost,
                            Instant measuredAt) {
        if (feedbackAt != null && measuredAt.isBefore(feedbackAt)) return;
        feedbackAt = measuredAt; error = null;
        if (lost || (bootId != null && !bootId.equals(boot))) {
            interrupted = true;
            error = "ESP32 reiniciado: volume parcial preservado. Feche o ramal manual e finalize.";
        } else if (!interrupted && measuredEndAt == null) {
            if (startPulses == null) { startPulses = initial; bootId = boot; startedAt = measuredAt; }
            if (initial != startPulses || current < startPulses) return;
            if (lastPulses != null && current < lastPulses) return;
            BigDecimal measuredLiters = BigDecimal.valueOf(current - startPulses).divide(pulsesPerLiter, 4, RoundingMode.HALF_UP);
            if (measuredLiters.precision() > 20) return;
            lastPulses = current;
            liters = measuredLiters;
        }
        if (status == Status.STARTING && phase.equals("pausada")) status = Status.RUNNING;
        if (status == Status.STOPPING && phase.equals("medida")) {
            status = Status.RESUMING; measuredEndAt = measuredAt;
        }
        if (status == Status.RESUMING && phase.equals("liberada")) {
            status = Status.COMPLETED; completedAt = Instant.now();
        }
    }
}
