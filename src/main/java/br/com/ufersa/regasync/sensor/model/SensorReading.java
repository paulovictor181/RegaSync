package br.com.ufersa.regasync.sensor.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name="sensor_readings", uniqueConstraints=@UniqueConstraint(name="uk_sensor_reading_message",columnNames={"sensor_id","message_id"}), indexes=@Index(name="idx_sensor_readings_sensor_measured",columnList="sensor_id,measured_at,id"))
@Getter
public class SensorReading {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="sensor_id",nullable=false) private Sensor sensor;
    @Column(name="message_id",nullable=false,length=128) private String messageId;
    @Column(name="measured_at",nullable=false) private Instant measuredAt;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
    @Column(name="raw_value",nullable=false) private long rawValue;
    @Column(name="interval_ms") private Long intervalMs;
    @Column(name="converted_value",nullable=false,precision=16,scale=4) private BigDecimal convertedValue;
    @Column(nullable=false,length=12) private String unit;
    @Column(name="calibration_version",nullable=false) private int calibrationVersion;
    protected SensorReading() { }
    public SensorReading(Sensor sensor,String messageId,Instant measuredAt,long rawValue,Long intervalMs,BigDecimal convertedValue) {
        this.sensor=sensor; this.messageId=messageId; this.measuredAt=measuredAt; this.receivedAt=Instant.now();
        this.rawValue=rawValue; this.intervalMs=intervalMs; this.convertedValue=convertedValue;
        this.unit=sensor.getType().unit(); this.calibrationVersion=sensor.getCalibrationVersion();
    }
}
