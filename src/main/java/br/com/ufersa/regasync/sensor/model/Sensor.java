package br.com.ufersa.regasync.sensor.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name="sensors", uniqueConstraints=@UniqueConstraint(name="uk_sensors_type_number", columnNames={"type", "number"}))
@Getter
public class Sensor {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=120) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private SensorType type;
    @Column(nullable=false) private Integer number;
    @Column(nullable=false) private boolean active;
    @Column(name="dry_adc") private Integer dryAdc;
    @Column(name="wet_adc") private Integer wetAdc;
    @Column(name="pulses_per_liter", precision=12, scale=4) private BigDecimal pulsesPerLiter;
    @Column(name="calibration_version", nullable=false) private int calibrationVersion;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    protected Sensor() { }
    public Sensor(String name, SensorType type, int number, Integer dryAdc, Integer wetAdc, BigDecimal pulsesPerLiter) {
        this.name=name.strip(); this.type=type; this.number=number; this.active=true;
        this.dryAdc=dryAdc; this.wetAdc=wetAdc; this.pulsesPerLiter=pulsesPerLiter;
        this.calibrationVersion=1; this.createdAt=Instant.now(); this.updatedAt=createdAt;
    }
    public String topic() { return type.topic(number); }
    public void update(String name, Integer dryAdc, Integer wetAdc, BigDecimal pulsesPerLiter, boolean active) {
        if (!java.util.Objects.equals(this.dryAdc,dryAdc) || !java.util.Objects.equals(this.wetAdc,wetAdc)
                || !java.util.Objects.equals(this.pulsesPerLiter,pulsesPerLiter)) calibrationVersion++;
        this.name=name.strip(); this.dryAdc=dryAdc; this.wetAdc=wetAdc;
        this.pulsesPerLiter=pulsesPerLiter; this.active=active; this.updatedAt=Instant.now();
    }
}
