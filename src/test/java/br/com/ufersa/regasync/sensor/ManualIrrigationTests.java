package br.com.ufersa.regasync.sensor;

import br.com.ufersa.regasync.irrigation.model.ManualIrrigation.Status;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.irrigation.service.ManualIrrigationService;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(username = "owner@example.com")
class ManualIrrigationTests extends IntegrationTestSupport {
    @Autowired ManualIrrigationRepository sessions;
    @Autowired ManualIrrigationService service;
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    Sensor flow;
    private static final String ACK = "/regasync/dispositivos/esp32/rega-manual/estado";

    @BeforeEach void setup() {
        sessions.deleteAll(); readings.deleteAll(); sensors.deleteAll();
        flow = sensors.saveAndFlush(new Sensor("Fluxo principal", SensorType.FLUXO, 1, null, null, new BigDecimal("450")));
    }
    @AfterEach void clean() { sessions.deleteAll(); }

    String ack(long id, long version, String phase, long initial, long count, boolean paused, boolean relay, boolean interrupted) {
        return json.writeValueAsString(Map.ofEntries(Map.entry("sessaoId", id), Map.entry("versao", version),
                Map.entry("fase", phase), Map.entry("sensorNumero", 1), Map.entry("pulsosInicio", initial),
                Map.entry("pulsosAtuais", count), Map.entry("automaticoPausado", paused), Map.entry("releLigado", relay),
                Map.entry("bootId", "boot-1"), Map.entry("interrompida", interrupted), Map.entry("medidoEm", Instant.now().toString())));
    }
    @Test void waitsForRelayOffAndCompletesOnlyAfterResumeConfirmation() {
        long id = service.start(flow.getId());
        assertThat(service.view(flow.getId()).status()).isEqualTo("STARTING");
        assertThat(service.pendingCommands()).singleElement().satisfies(c -> {
            assertThat(c.topic()).endsWith("/esp32/rega-manual/comando");
            assertThat(c.payload()).contains("pausar");
        });
        service.acknowledge(ACK, ack(id, id*3-2, "pausada", 100, 100, true, true, false));
        assertThat(service.view(flow.getId()).status()).isEqualTo("STARTING");
        service.acknowledge(ACK, ack(id, id*3-2, "pausada", 100, 100, true, false, false));
        assertThat(service.view(flow.getId()).status()).isEqualTo("RUNNING");
        service.acknowledge(ACK, ack(id, id*3-2, "pausada", 100, 550, true, false, false));
        assertThat(service.view(flow.getId()).liters()).isEqualByComparingTo("1");
        service.acknowledge(ACK, ack(id, id*3-2, "pausada", 100, 550, true, false, false));
        assertThat(service.view(flow.getId()).liters()).isEqualByComparingTo("1");
        service.finish(flow.getId(), id);
        assertThat(service.view(flow.getId()).status()).isEqualTo("STOPPING");
        service.acknowledge(ACK, ack(id, id*3-1, "medida", 100, 775, true, false, false));
        assertThat(service.view(flow.getId()).status()).isEqualTo("RESUMING");
        assertThat(service.view(flow.getId()).liters()).isEqualByComparingTo("1.5");
        service.acknowledge(ACK, ack(id, id*3, "liberada", 100, 775, false, true, false));
        assertThat(service.view(flow.getId()).status()).isEqualTo("COMPLETED");
        assertThat(service.view(flow.getId()).canStart()).isTrue();
        assertThat(service.pendingCommands()).isEmpty();
    }
    @Test void ignoresStaleCommandsWrongDevicesAndRegressiveCounters() {
        long id = service.start(flow.getId());
        service.acknowledge("/regasync/dispositivos/outro/rega-manual/estado", ack(id,id*3-2,"pausada",100,100,true,false,false));
        service.acknowledge(ACK, ack(id,id*3,"liberada",100,100,false,false,false));
        assertThat(service.view(flow.getId()).status()).isEqualTo("STARTING");
        service.acknowledge(ACK, ack(id,id*3-2,"pausada",100,550,true,false,false));
        service.acknowledge(ACK, ack(id,id*3-2,"pausada",100,200,true,false,false));
        assertThat(service.view(flow.getId()).liters()).isEqualByComparingTo("1");
        service.acknowledge(ACK, "{\"sessaoId\":"+id+",\"versao\":1.5}");
        assertThat(service.view(flow.getId()).liters()).isEqualByComparingTo("1");
    }
    @Test void deviceRebootPreservesPartialVolumeAndRequiresExplicitFinish() {
        long id = service.start(flow.getId());
        service.acknowledge(ACK, ack(id,id*3-2,"pausada",100,550,true,false,false));
        service.acknowledge(ACK, ack(id,id*3-2,"pausada",100,100,true,false,true));
        var state = service.view(flow.getId());
        assertThat(state.interrupted()).isTrue();
        assertThat(state.liters()).isEqualByComparingTo("1");
        assertThat(state.canStart()).isFalse();
        service.finish(flow.getId(), id);
        service.acknowledge(ACK, ack(id,id*3-1,"medida",100,100,true,false,true));
        service.acknowledge(ACK, ack(id,id*3,"liberada",100,100,false,false,true));
        assertThat(service.view(flow.getId()).status()).isEqualTo("COMPLETED");
        assertThat(service.view(flow.getId()).liters()).isEqualByComparingTo("1");
    }
    @Test void onlyOneSessionPerDeviceAndOnlyFlowSensorsAreAccepted() {
        service.start(flow.getId());
        assertThatThrownBy(() -> service.start(flow.getId())).isInstanceOf(IllegalArgumentException.class);
        Sensor moisture = sensors.saveAndFlush(new Sensor("Umidade",SensorType.UMIDADE,1,3000,1000,null));
        assertThatThrownBy(() -> service.start(moisture.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void authenticatedOwnerControlsSessionAndCsrfIsRequired() throws Exception {
        mvc.perform(get("/sensores/"+flow.getId()+"/rega-manual")).andExpect(status().isOk());
        mvc.perform(post("/sensores/"+flow.getId()+"/rega-manual/iniciar")).andExpect(status().isForbidden());
        mvc.perform(post("/sensores/"+flow.getId()+"/rega-manual/iniciar").with(csrf())).andExpect(status().is3xxRedirection());
        long id = sessions.findAll().getFirst().getId();
        mvc.perform(post("/sensores/"+flow.getId()+"/rega-manual/"+id+"/finalizar").with(csrf()).with(user("other@example.com"))).andExpect(status().isForbidden());
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(Status.STARTING);
        mvc.perform(get("/api/regas-manuais/"+flow.getId()).with(user("other@example.com")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canFinish").value(false));
        mvc.perform(post("/sensores/"+flow.getId()+"/rega-manual/"+id+"/finalizar").with(csrf()).with(user("admin@example.com").roles("ADMIN"))).andExpect(status().is3xxRedirection());
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(Status.STOPPING);
    }
    @Test void historyIsIsolatedByOwnerAndPaginatedInTheDatabase() throws Exception {
        for (int i=0; i<12; i++) {
            long id = service.start(flow.getId());
            service.finish(flow.getId(),id);
            service.acknowledge(ACK,ack(id,id*3-1,"medida",0,0,true,false,false));
            service.acknowledge(ACK,ack(id,id*3,"liberada",0,0,false,false,false));
        }
        assertThat(service.history(flow.getId(),0).getTotalElements()).isEqualTo(12);
        assertThat(service.history(flow.getId(),0).getContent()).hasSize(10);
        assertThat(service.history(flow.getId(),1).getContent()).hasSize(2);
        mvc.perform(get("/sensores/"+flow.getId()+"/rega-manual").with(user("other@example.com")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhuma sessão registrada")));
    }
}
