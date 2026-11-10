package br.com.ufersa.regasync.sensor;

import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.sensor.service.*;
import br.com.ufersa.regasync.dashboard.service.WaterConsumptionService;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;

@WithMockUser(roles="USER")
class FlowQualityTests extends IntegrationTestSupport {
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired SensorService sensorService;
    @Autowired SensorChartService charts;
    @Autowired WaterConsumptionService water;
    @Autowired ManualIrrigationRepository manual;
    @Autowired MockMvc mvc;
    final ReportPeriod period=new ReportPeriod(LocalDate.of(2026,11,5),LocalDate.of(2026,11,5));
    Sensor sensor;
    @BeforeEach void setup() {
        manual.deleteAll();readings.deleteAll();sensors.deleteAll();
        sensor=sensors.saveAndFlush(new Sensor("Fluxo",SensorType.FLUXO,1,null,null,BigDecimal.valueOf(1000)));
    }
    @AfterEach void cleanup() { manual.deleteAll(); }
    void sample(String id,int second,long pulses,long interval) {
        var value=BigDecimal.valueOf(pulses).multiply(BigDecimal.valueOf(60000)).divide(BigDecimal.valueOf(interval*1000),4,java.math.RoundingMode.HALF_UP);
        readings.saveAndFlush(new SensorReading(sensor,id,period.start().plusSeconds(second),pulses,interval,value));
    }
    @Test void pulsesConvertToLitersAndDurationOnlyChangesRate() {
        sample("one",3600,1000,60000);
        sample("two",3660,1000,30000);
        assertThat(readings.findAll()).allSatisfy(r->assertThat(r.getVolumeLiters()).isEqualByComparingTo("1"));
        assertThat(water.consumption(period).total()).isEqualByComparingTo("2");
    }
    @Test void excludesHighAndTinyFlowsButKeepsStableFlowAndZeroAndMarksChart() throws Exception {
        sample("high",3600,400000,60000);
        sample("tiny",3660,20,60000);
        sample("good",3720,2000,60000);
        sample("stable",3780,2000,60000);
        sample("zero",3840,0,60000);
        var result=water.consumption(period);
        assertThat(result.total()).isEqualByComparingTo("4");
        assertThat(result.excludedSamples()).isEqualTo(2);
        assertThat(result.excludedLiters()).isEqualByComparingTo("400.02");
        assertThat(readings.count()).isEqualTo(5);
        var chart=charts.charts(List.of(sensor.getId()),period.start(),period.end()).get(sensor.getId());
        assertThat(chart.interferenceSamples()).isEqualTo(2);
        assertThat(chart.points()).filteredOn(SensorChartService.Point::interference).hasSize(2);
        String html=mvc.perform(get("/dashboard").param("from","2026-11-05").param("to","2026-11-05"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("4,000 L","Possível interferência","Vazão acima do limite","Vazão abaixo do limite","#d97706","/grafico?");
    }
    @Test void acceptsBoundaryValuesAndRejectsManualNoiseWithoutMovingItToAutomatic() {
        sample("minimum",3600,100,60000);sample("maximum",3660,30000,60000);
        assertThat(water.consumption(period).total()).isEqualByComparingTo("30.1");
        readings.deleteAll();
        sample("manual-noise",3600,20,60000);
        var session=new ManualIrrigation(sensor,"private@example.com","esp32");
        session.acknowledge("pausada",0,0,"boot",false,period.start().plusSeconds(3540));
        session.acknowledge("pausada",0,20,"boot",false,period.start().plusSeconds(3600));
        manual.saveAndFlush(session);
        var result=water.consumption(period);
        assertThat(result.automatic()).isZero();assertThat(result.manual()).isZero();
        assertThat(result.excludedManualSessions()).isEqualTo(1);
        // Even valid flow during a suspect manual session must not become automatic consumption.
        sample("during-manual",3590,1000,10000);
        sample("pending-confirmation",4000,1000,10000);
        assertThat(water.consumption(period).automatic()).isZero();
    }
    @Test void expandedGraphHasMinuteResolutionBoundedQueriesAndPreservesPeriod() throws Exception {
        sample("first",3600,1000,60000);sample("second",3660,2000,60000);
        var hourly=charts.charts(List.of(sensor.getId()),period.start(),period.end(),"hour").get(sensor.getId());
        var minute=charts.charts(List.of(sensor.getId()),period.start(),period.end(),"minute").get(sensor.getId());
        assertThat(hourly.points()).hasSize(1);assertThat(minute.points()).hasSize(2);
        String html=mvc.perform(get("/sensores/"+sensor.getId()+"/grafico").param("from","2026-11-05").param("to","2026-11-05").param("interval","minute"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Médias por minuto","1 minuto","05/11/2026 00:00","chart-point-detail","max-w-[100rem]");
        String precise=mvc.perform(get("/sensores/"+sensor.getId()+"/grafico").param("startAt","2026-11-05T00:59").param("endAt","2026-11-05T01:00:30").param("interval","minute"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(precise).contains("Média: 1,00").doesNotContain("Média: 2,00");
        String bounded=mvc.perform(get("/sensores/"+sensor.getId()+"/grafico").param("from","2026-01-01").param("to","2026-11-05").param("interval","minute"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bounded).contains("até 4.096 intervalos");
        mvc.perform(get("/sensores/"+sensor.getId()+"/grafico").with(anonymous())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/sensores/9999999/grafico")).andExpect(status().isNotFound());
    }
}
