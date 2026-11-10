package br.com.ufersa.regasync.dashboard;

import br.com.ufersa.regasync.dashboard.demo.DemoDashboardService;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.pump.model.PumpEvent;
import br.com.ufersa.regasync.pump.repository.*;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="app.demo.enabled=true")
@WithMockUser(roles="USER")
class DemoDashboardTests extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired DemoDashboardService demo;
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired ManualIrrigationRepository manual;
    @Autowired PumpEventRepository events;
    @Autowired PumpStateRepository states;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach @AfterEach void clean() {
        manual.deleteAll();readings.deleteAll();events.deleteAll();states.deleteAll();sensors.deleteAll();
    }
    @Test void rendersPreviewInMemoryWithoutChangingAnyTable() throws Exception {
        sensors.saveAndFlush(new Sensor("Cadastro real",SensorType.UMIDADE,1,3000,1000,null));
        var before=counts();
        String html=page("2026-11-09","2026-11-09");
        assertThat(html).contains("Modo demonstração", "dados fictícios", "75,000 L", "25,000 L", "75,00%", "25,00%", "<svg", "Umidade · demonstração", "Fluxo · demonstração", "action=\"/dashboard/demo\"");
        assertThat(html).doesNotContain("/api/sensores/atuais", "/rega-manual", "/sensores/1", "/bomba/historico");
        page("2026-11-09","2026-11-10");
        assertThat(counts()).isEqualTo(before);
    }
    @Test void filtersChangeOnlyTheSimulationAndRealDashboardRemainsReal() throws Exception {
        assertThat(page("2026-11-09","2026-11-10")).contains("150,000 L","50,000 L","09/11/2026 a 10/11/2026");
        String real=mvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(real).contains("Testar com dados fictícios","Total: 0,000 L").doesNotContain("Umidade · demonstração", "75,000 L");
        mvc.perform(get("/dashboard/demo").param("from","2026-11-10").param("to","2026-11-09")).andExpect(status().isBadRequest());
    }
    @Test void readingsSessionsAndPumpDataEachPreventPreview() throws Exception {
        var sensor=sensors.saveAndFlush(new Sensor("Real",SensorType.FLUXO,1,null,null,BigDecimal.valueOf(1000)));
        readings.saveAndFlush(new SensorReading(sensor,"real",Instant.now(),10,1000L,BigDecimal.valueOf(0.6)));
        blocked();readings.deleteAll();
        manual.saveAndFlush(new ManualIrrigation(sensor,"real@example.com","esp32"));
        blocked();manual.deleteAll();
        events.saveAndFlush(new PumpEvent("esp32",false,true,Instant.now()));
        blocked();events.deleteAll();
        jdbc.update("insert into pump_states(device,running) values ('esp32',false)");
        blocked();
    }
    @Test void expandedMockGraphUsesFiltersWithoutReadingOrWritingRealSensors() throws Exception {
        var before=counts();
        String html=mvc.perform(get("/dashboard/demo/grafico/1").param("from","2026-11-05").param("to","2026-11-05").param("interval","minute"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Modo demonstração", "Médias por minuto", "Voltar à demonstração").doesNotContain("/sensores/1");
        assertThat(counts()).isEqualTo(before);
    }

    @Test void anonymousUsersCannotAccessMockData() throws Exception {
        mvc.perform(get("/dashboard/demo").with(anonymous())).andExpect(status().is3xxRedirection());
    }
    private void blocked() throws Exception {
        assertThat(demo.available()).isFalse();
        mvc.perform(get("/dashboard/demo")).andExpect(status().isConflict());
        String real=mvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(real).doesNotContain("Testar com dados fictícios", "Modo demonstração");
    }
    private String page(String from,String to) throws Exception {
        return mvc.perform(get("/dashboard/demo").param("from",from).param("to",to)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private java.util.Map<String,Object> counts() {
        return jdbc.queryForMap("select (select count(*) from user_accounts) as users, (select count(*) from sensors) as sensors, (select count(*) from sensor_readings) as readings, (select count(*) from manual_irrigations) as manual, (select count(*) from pump_events) as events, (select count(*) from pump_states) as states");
    }
}
