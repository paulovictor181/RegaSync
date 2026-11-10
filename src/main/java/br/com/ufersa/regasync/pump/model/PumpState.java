package br.com.ufersa.regasync.pump.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity @Table(name="pump_states") @Getter
public class PumpState {
    @Id @Column(length=128) private String device;
    @Column(nullable=false) private boolean running;
    @Column(name="measured_at") private Instant measuredAt;
    protected PumpState() {}
    public void update(boolean running, Instant at) { this.running=running;this.measuredAt=at; }
}
