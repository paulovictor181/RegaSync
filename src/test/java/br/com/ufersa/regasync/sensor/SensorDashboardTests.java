package br.com.ufersa.regasync.sensor;

import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.sensor.service.SensorChartService;
import br.com.ufersa.regasync.shared.ui.BrazilianDates;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SensorDashboardTests extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired SensorChartService charts;

    @BeforeEach void clear() { readings.deleteAll(); sensors.deleteAll(); }

    @Test void anonymousUsersCannotReadDashboardOrHistory() throws Exception {
        mvc.perform(get("/sensores")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/sensores/1")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/sensores/atuais").param("ids", "1")).andExpect(status().is3xxRedirection());
    }

    @Test @WithMockUser(roles="USER")
    void chartsAggregateInDatabaseAndStayWithinSensorAndTimeWindow() throws Exception {
        var moisture = sensors.saveAndFlush(new Sensor("Umidade jardim", SensorType.UMIDADE, 1, 3000, 1000, null));
        var flow = sensors.saveAndFlush(new Sensor("Fluxo bomba", SensorType.FLUXO, 1, null, null, new BigDecimal("1000")));
        Instant hour = Instant.now().minus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        save(moisture,"m1",hour.plusSeconds(10),20);
        save(moisture,"m2",hour.plusSeconds(20),40);
        save(moisture,"old",hour.minus(Duration.ofDays(2)),99);
        save(flow,"f1",hour.plusSeconds(10),5);
        var result=charts.charts(java.util.List.of(moisture.getId(),flow.getId()));
        assertThat(result.get(moisture.getId()).samples()).isEqualTo(2);
        assertThat(result.get(moisture.getId()).points()).singleElement().satisfies(p -> assertThat(p.label()).contains("30,00"));
        assertThat(result.get(flow.getId()).points()).singleElement().satisfies(p -> assertThat(p.label()).contains("5,00"));
        String html=mvc.perform(get("/dashboard").param("from",hour.atZone(BrazilianDates.ZONE).toLocalDate().toString()).param("to",Instant.now().atZone(BrazilianDates.ZONE).toLocalDate().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Acionamentos da bomba", "Evolução", "<svg", "30,00", "L/min");
        assertThat(html).doesNotContain("name=\"q\"", "name=\"sort\"", "Cadastrar sensor", "Configurar");
        String inventory=mvc.perform(get("/sensores")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inventory).contains("<table", "Cadastrado em", "Umidade jardim", "name=\"q\"");
        assertThat(inventory).doesNotContain("Acionamentos da bomba", "Evolução ·", "Leituras mais recentes");
        mvc.perform(get("/sensores/"+moisture.getId())).andExpect(status().isOk());
        mvc.perform(get("/api/sensores/atuais").param("ids",moisture.getId()+","+flow.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$."+moisture.getId()+".valor").value(40))
                .andExpect(jsonPath("$."+flow.getId()+".valor").value(5));
    }

    @Test @WithMockUser(roles="USER")
    void historyFiltersDatesInBrazilAndPreservesSortAndPagingWithoutOtherSensors() throws Exception {
        var sensor=sensors.saveAndFlush(new Sensor("Umidade",SensorType.UMIDADE,1,3000,1000,null));
        var other=sensors.saveAndFlush(new Sensor("Outro",SensorType.UMIDADE,2,3000,1000,null));
        Instant start=Instant.parse("2026-11-09T03:00:00Z");
        save(sensor,"before",start.minusSeconds(1),99);
        save(sensor,"first",start,10);
        save(sensor,"second",start.plusSeconds(1),20);
        save(sensor,"after",start.plus(Duration.ofDays(1)),99);
        save(other,"unrelated",start,99);
        var response=mvc.perform(get("/sensores/"+sensor.getId()).param("from","2026-11-09").param("to","2026-11-09")
                .param("size","1").param("sort","convertedValue").param("direction","asc").param("page","1"))
                .andExpect(status().isOk()).andReturn();
        var page=(org.springframework.data.domain.Page<?>)response.getModelAndView().getModel().get("readings");
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(((SensorReading)page.getContent().getFirst()).getConvertedValue()).isEqualByComparingTo("20");
        assertThat(response.getResponse().getContentAsString()).contains("09/11/2026 00:00:01", "sort=convertedValue", "direction=asc", "page=0");
        mvc.perform(get("/sensores/"+sensor.getId()).param("from","2026-11-10").param("to","2026-11-09"))
                .andExpect(status().isBadRequest());
    }

    @Test @WithMockUser(roles="USER")
    void sensorPagingKeepsFiltersAndRegistrationSort() throws Exception {
        sensors.saveAndFlush(new Sensor("Jardim A",SensorType.UMIDADE,1,3000,1000,null));
        sensors.saveAndFlush(new Sensor("Jardim B",SensorType.UMIDADE,2,3000,1000,null));
        String html=mvc.perform(get("/sensores").param("q","Jardim").param("size","1")
                .param("sort","createdAt").param("direction","desc").param("type","UMIDADE").param("active","true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("sort=createdAt", "direction=desc", "q=Jardim", "type=UMIDADE", "active=true", "page=1");
    }

    @Test @WithMockUser(roles="USER")
    void dashboardCountsAllSensorsAndPaginatesOnlyActiveSensors() throws Exception {
        for(int i=1;i<=8;i++) sensors.saveAndFlush(new Sensor("Umidade "+i,SensorType.UMIDADE,i,3000,1000,null));
        var inactive=new Sensor("Inativo",SensorType.FLUXO,1,null,null,BigDecimal.valueOf(1000));
        inactive.update("Inativo",null,null,BigDecimal.valueOf(1000),false);
        sensors.saveAndFlush(inactive);
        var response=mvc.perform(get("/dashboard").param("page","1")).andExpect(status().isOk()).andReturn();
        var model=response.getModelAndView().getModel();
        assertThat(model).containsEntry("totalSensors",9L).containsEntry("activeSensors",8L)
                .containsEntry("humiditySensors",8L).containsEntry("flowSensors",0L);
        var page=(org.springframework.data.domain.Page<?>)model.get("sensors");
        assertThat(page.getTotalElements()).isEqualTo(8);
        assertThat(page.getContent()).hasSize(2);
        assertThat(response.getResponse().getContentAsString()).contains("/dashboard?page=0", "Umidade 7", "Umidade 8")
                .doesNotContain("Inativo");
    }

    @Test @WithMockUser(roles="USER")
    void demoIsDisabledByDefault() throws Exception {
        mvc.perform(get("/dashboard/demo")).andExpect(status().isNotFound());
        String html=mvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("Testar com dados fictícios", "Modo demonstração");
    }

    @Test void brazilianDatesConvertUtcAndHandleDayRollover() {
        assertThat(new BrazilianDates().format(Instant.parse("2026-11-09T01:02:03Z"))).isEqualTo("08/11/2026 22:02:03");
    }

    private void save(Sensor sensor,String id,Instant at,int value) {
        readings.saveAndFlush(new SensorReading(sensor,id,at,1000L,sensor.getType()==SensorType.FLUXO?1000L:null,BigDecimal.valueOf(value)));
    }
}
