package br.com.ufersa.regasync.sensor.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

@Entity @Table(name="flow_quality_settings") @Getter
public class FlowQualitySettings {
    @Id private Integer id=1;
    @Column(name="minimum_flow",nullable=false,precision=12,scale=4) private BigDecimal minimum;
    @Column(name="maximum_flow",nullable=false,precision=12,scale=4) private BigDecimal maximum;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected FlowQualitySettings() {}
    public FlowQualitySettings(BigDecimal minimum,BigDecimal maximum) {
        this.minimum=minimum;this.maximum=maximum;this.updatedAt=Instant.now();
    }
}
