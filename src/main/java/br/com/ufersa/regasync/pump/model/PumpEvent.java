package br.com.ufersa.regasync.pump.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity @Table(name="pump_events") @Getter
public class PumpEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=128) private String device;
    @Column(nullable=false) private boolean running;
    @Column(name="initial_state", nullable=false) private boolean initialState;
    @Column(name="measured_at", nullable=false) private Instant measuredAt;
    @Column(name="received_at", nullable=false) private Instant receivedAt;
    protected PumpEvent() {}
    public PumpEvent(String device, boolean running, boolean initialState, Instant measuredAt) {
        this.device=device;this.running=running;this.initialState=initialState;
        this.measuredAt=measuredAt;this.receivedAt=Instant.now();
    }
}
