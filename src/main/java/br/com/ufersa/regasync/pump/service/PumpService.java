package br.com.ufersa.regasync.pump.service;

import br.com.ufersa.regasync.pump.model.PumpEvent;
import br.com.ufersa.regasync.pump.repository.*;
import br.com.ufersa.regasync.sensor.mqtt.DeviceFeedback;
import java.time.*;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PumpService {
    private final PumpStateRepository states;
    private final PumpEventRepository events;
    private final ObjectMapper mapper;
    private final String device;
    public PumpService(PumpStateRepository states,PumpEventRepository events,ObjectMapper mapper,
                       @Value("${app.mqtt.device-username:esp32}") String device) {
        this.states=states;this.events=events;this.mapper=mapper;this.device=device;
    }
    @EventListener @Transactional
    public void receive(DeviceFeedback feedback) {
        if(!feedback.topic().equals("/regasync/dispositivos/"+device+"/estado")) return;
        boolean running;
        Instant at;
        try {
            var json=mapper.readTree(feedback.payload());
            if(!json.path("online").isBoolean() || !json.path("online").asBoolean() || !json.path("releLigado").isBoolean()) return;
            running=json.path("releLigado").asBoolean();
            at=Instant.parse(json.path("medidoEm").asString());
            if(at.isAfter(Instant.now().plusSeconds(30))) return;
        } catch(IllegalArgumentException | DateTimeException | tools.jackson.core.JacksonException ignored) { return; }
        states.initialize(device);
        var state=states.lock(device);
        if(state.getMeasuredAt()!=null && !at.isAfter(state.getMeasuredAt())) return;
        if(state.getMeasuredAt()==null || state.isRunning()!=running)
            events.save(new PumpEvent(device,running,state.getMeasuredAt()==null,at));
        state.update(running,at);
    }
    @Transactional(readOnly=true)
    public Page<PumpEvent> history(int page) {
        return events.findByDevice(device,PageRequest.of(Math.max(0,page),10,Sort.by("measuredAt").descending().and(Sort.by("id").descending())));
    }
    @Transactional(readOnly=true)
    public Optional<PumpEvent> latest() { return events.findFirstByDeviceOrderByMeasuredAtDescIdDesc(device); }
}
