package br.com.ufersa.regasync.dashboard;

import br.com.ufersa.regasync.dashboard.service.WaterConsumptionService;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles="USER")
class WaterConsumptionTests extends IntegrationTestSupport {
    @Autowired WaterConsumptionService service;
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired ManualIrrigationRepository sessions;
    @Autowired MockMvc mvc;
    final ReportPeriod period=new ReportPeriod(LocalDate.of(2026,11,9),LocalDate.of(2026,11,9));
    Sensor flow;
    @BeforeEach void setup() {
        sessions.deleteAll();readings.deleteAll();sensors.deleteAll();
        flow=sensors.saveAndFlush(new Sensor("Fluxo principal",SensorType.FLUXO,1,null,null,BigDecimal.valueOf(1000)));
    }
    @AfterEach void cleanup() { sessions.deleteAll(); }
    void reading(String id,Instant at,long pulses,long interval) {
        readings.saveAndFlush(new SensorReading(flow,id,at,pulses,interval,BigDecimal.valueOf(pulses*60000d/interval/1000)));
    }
    void manual(String device,Instant start,Instant end,long pulses) {
        var session=new ManualIrrigation(flow,"private@example.com",device);
        session.acknowledge("pausada",0,0,"boot",false,start);
        session.acknowledge("pausada",0,pulses,"boot",false,end);
        sessions.saveAndFlush(session);
    }
    @Test void splitsMeasuredVolumeWithoutDoubleCountingAndPreservesCalibrationSnapshot() throws Exception {
        Instant at=period.start().plusSeconds(3600);
        reading("total",at,100000,600000);
        manual("esp32",at.minusSeconds(150),at,25000);
        manual("other",at.minusSeconds(30),at,500000);
        var result=service.consumption(period);
        assertThat(result.automatic()).isEqualByComparingTo("75");
        assertThat(result.manual()).isEqualByComparingTo("25");
        assertThat(result.total()).isEqualByComparingTo("100");
        assertThat(result.automaticPercent()).isEqualByComparingTo("75");
        assertThat(result.manualPercent()).isEqualByComparingTo("25");
        flow.update("Fluxo principal",null,null,BigDecimal.valueOf(2000),true);sensors.saveAndFlush(flow);
        assertThat(service.consumption(period).total()).isEqualByComparingTo("100");
        String html=mvc.perform(get("/dashboard").param("from","2026-11-09").param("to","2026-11-09"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("75,000 L","25,000 L","75,00%","25,00%","water-automatic","water-manual");
        assertThat(html).doesNotContain("private@example.com","other");
    }
    @Test void emptyAndManualOnlyPeriodsHaveSafePercentages() {
        var empty=service.consumption(period);
        assertThat(empty.total()).isZero();assertThat(empty.automaticPercent()).isZero();assertThat(empty.manualPercent()).isZero();
        manual("esp32",period.start().plusSeconds(1),period.start().plusSeconds(61),1000);
        var incomplete=service.consumption(period);
        assertThat(incomplete.automatic()).isZero();assertThat(incomplete.manualPercent()).isEqualByComparingTo("100");
        assertThat(incomplete.incomplete()).isTrue();
    }
    @Test void proratesIntervalsAtBrazilianDayBoundaryAndExcludesOutsideRecords() {
        reading("cross",period.end().plusSeconds(30),10000,60000);
        reading("after",period.end().plusSeconds(120),50000,60000);
        reading("before",period.start(),50000,60000);
        manual("esp32",period.end().minusSeconds(6),period.end().plusSeconds(6),2000);
        var result=service.consumption(period);
        assertThat(result.total()).isEqualByComparingTo("5");
        assertThat(result.manual()).isEqualByComparingTo("1");
        assertThat(result.automatic()).isEqualByComparingTo("4");
    }
    @Test void chartsAndHistoryUseSameFilterAndRejectInvalidPeriods() throws Exception {
        var moisture=sensors.saveAndFlush(new Sensor("Umidade",SensorType.UMIDADE,1,3000,1000,null));
        readings.saveAndFlush(new SensorReading(moisture,"old",period.start().plusSeconds(10),2000,null,BigDecimal.valueOf(30)));
        readings.saveAndFlush(new SensorReading(moisture,"outside",period.end(),2000,null,BigDecimal.valueOf(99)));
        for(String url:java.util.List.of("/dashboard","/sensores/"+moisture.getId())) {
            String html=mvc.perform(get(url).param("from","2026-11-09").param("to","2026-11-09"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("09/11/2026 a 09/11/2026","Média: 30,00").doesNotContain("Média: 99,00");
            mvc.perform(get(url).param("from","2026-11-10").param("to","2026-11-09")).andExpect(status().isBadRequest());
        }
    }
    @Test void longPeriodsUseDailyBucketsAndKeepDatesOnMonitoringPagination() throws Exception {
        for(int i=2;i<9;i++) sensors.saveAndFlush(new Sensor("Umidade "+i,SensorType.UMIDADE,i,3000,1000,null));
        String html=mvc.perform(get("/dashboard").param("from","2026-11-01").param("to","2026-11-30"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Médias por dia","from=2026-11-01","to=2026-11-30","page=1");
    }
}
