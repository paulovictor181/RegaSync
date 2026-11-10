package br.com.ufersa.regasync.pump;

import br.com.ufersa.regasync.pump.repository.*;
import br.com.ufersa.regasync.pump.service.PumpService;
import br.com.ufersa.regasync.sensor.mqtt.DeviceFeedback;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PumpHistoryTests extends IntegrationTestSupport {
    @Autowired PumpService service;
    @Autowired PumpEventRepository events;
    @Autowired PumpStateRepository states;
    @Autowired MockMvc mvc;
    @BeforeEach @AfterEach void clean() { events.deleteAll();states.deleteAll(); }

    @Test @WithMockUser(roles="USER")
    void recordsOnlyChangesIgnoresDuplicatesStaleAndOtherDevices() throws Exception {
        Instant start=Instant.parse("2026-11-01T12:00:00Z");
        receive("esp32",false,start);
        receive("esp32",false,start.plusSeconds(1));
        receive("esp32",true,start.plusSeconds(2));
        receive("esp32",true,start.plusSeconds(2));
        receive("esp32",false,start.plusSeconds(1));
        receive("other",false,start.plusSeconds(3));
        receive("esp32",false,Instant.now().plusSeconds(90));
        service.receive(new DeviceFeedback("/regasync/dispositivos/esp32/estado","{\"online\":true,\"releLigado\":\"false\"}"));
        assertThat(events.count()).isEqualTo(2);
        assertThat(service.latest().orElseThrow().isRunning()).isTrue();
        assertThat(service.latest().orElseThrow().isInitialState()).isFalse();
        String html=mvc.perform(get("/bomba/historico")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Bomba ligada", "Bomba desligada (estado inicial)", "01/11/2026 09:00:02");
    }

    @Test @WithMockUser(roles="USER")
    void pumpEventsArePaginatedAndInitialRunningStateIsNotCalledAnActivation() throws Exception {
        Instant start=Instant.parse("2026-11-01T12:00:00Z");
        for(int i=0;i<12;i++) receive("esp32",i%2==0,start.plusSeconds(i));
        assertThat(service.history(0).getTotalElements()).isEqualTo(12);
        assertThat(service.history(1).getContent()).hasSize(2);
        assertThat(service.history(1).getContent().getLast().isInitialState()).isTrue();
        String html=mvc.perform(get("/bomba/historico").param("page","1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Bomba ligada (estado inicial)", "page=0", "Página 2 de 2");
    }

    @Test void anonymousCannotReadPumpHistory() throws Exception {
        mvc.perform(get("/bomba/historico")).andExpect(status().is3xxRedirection());
    }

    private void receive(String device,boolean running,Instant at) {
        service.receive(new DeviceFeedback("/regasync/dispositivos/"+device+"/estado",
                "{\"online\":true,\"releLigado\":"+running+",\"medidoEm\":\""+at+"\"}"));
    }
}
