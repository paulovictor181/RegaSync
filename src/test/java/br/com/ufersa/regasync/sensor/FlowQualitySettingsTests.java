package br.com.ufersa.regasync.sensor;

import br.com.ufersa.regasync.sensor.config.FlowQualityPolicy;
import br.com.ufersa.regasync.sensor.form.FlowQualityForm;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.sensor.service.SensorChartService;
import br.com.ufersa.regasync.dashboard.service.WaterConsumptionService;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

class FlowQualitySettingsTests extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired FlowQualitySettingsRepository settings;
    @Autowired FlowQualityPolicy policy;
    @Autowired SensorReadingRepository readings;
    @Autowired SensorRepository sensors;
    @Autowired WaterConsumptionService water;
    @Autowired SensorChartService charts;
    @BeforeEach @AfterEach void clean() { settings.deleteAll();readings.deleteAll();sensors.deleteAll(); }
    @Test @WithMockUser(roles="USER")
    void usersCannotReadOrChangeSettingsEvenDirectly() throws Exception {
        var form=new FlowQualityForm();form.setMinimum(BigDecimal.ZERO);form.setMaximum(BigDecimal.TEN);
        assertThatThrownBy(()->policy.save(form)).isInstanceOf(AccessDeniedException.class);
        mvc.perform(get("/admin/configuracoes/fluxo")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/configuracoes/fluxo").with(csrf()).param("minimum","0.01").param("maximum","100"))
                .andExpect(status().isForbidden());
        assertThat(settings.count()).isZero();
    }
    @Test @WithMockUser(roles="ADMIN")
    void formSavesLimitsAndTheyApplyImmediatelyToHistoricalRecords() throws Exception {
        var sensor=sensors.saveAndFlush(new Sensor("Fluxo",SensorType.FLUXO,1,null,null,BigDecimal.valueOf(1000)));
        var period=new ReportPeriod(LocalDate.of(2026,11,5),LocalDate.of(2026,11,5));
        readings.saveAndFlush(new SensorReading(sensor,"high",period.start().plusSeconds(3600),40000,60000L,BigDecimal.valueOf(40)));
        readings.saveAndFlush(new SensorReading(sensor,"low",period.start().plusSeconds(3660),80,60000L,new BigDecimal("0.08")));
        assertThat(water.consumption(period).total()).isZero();
        assertThat(policy.limits().maximum()).isEqualByComparingTo("30");
        mvc.perform(get("/admin/configuracoes/fluxo")).andExpect(status().isOk());
        mvc.perform(post("/admin/configuracoes/fluxo").with(csrf()).param("minimum","0.05").param("maximum","50"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/configuracoes/fluxo"));
        assertThat(settings.findById(1).orElseThrow().getMinimum()).isEqualByComparingTo("0.05");
        assertThat(policy.limits().maximum()).isEqualByComparingTo("50");
        assertThat(water.consumption(period).total()).isEqualByComparingTo("40.08");
        assertThat(charts.charts(List.of(sensor.getId()),period.start(),period.end()).get(sensor.getId()).interferenceSamples()).isZero();
        assertThat(readings.count()).isEqualTo(2);
    }
    @Test @WithMockUser(roles="ADMIN")
    void invalidRangesAndPrecisionDoNotOverwriteSavedSettings() throws Exception {
        mvc.perform(post("/admin/configuracoes/fluxo").with(csrf()).param("minimum","0.1").param("maximum","30")).andExpect(status().is3xxRedirection());
        for(String[] values:List.of(new String[]{"-1","30"},new String[]{"30","30"},new String[]{"31","30"},new String[]{"0.00001","30"},new String[]{"0.1","100000000"})) {
            mvc.perform(post("/admin/configuracoes/fluxo").with(csrf()).param("minimum",values[0]).param("maximum",values[1]))
                    .andExpect(status().isOk()).andExpect(model().attributeHasErrors("qualityForm"));
        }
        assertThat(policy.limits().minimum()).isEqualByComparingTo("0.1");
        assertThat(policy.limits().maximum()).isEqualByComparingTo("30");
        mvc.perform(post("/admin/configuracoes/fluxo").param("minimum","0").param("maximum","999"))
                .andExpect(status().isForbidden());
    }
}
