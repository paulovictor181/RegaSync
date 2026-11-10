package br.com.ufersa.regasync.sensor;

import br.com.ufersa.regasync.sensor.form.SensorForm;
import br.com.ufersa.regasync.sensor.model.Sensor;
import br.com.ufersa.regasync.sensor.model.SensorReading;
import br.com.ufersa.regasync.sensor.model.SensorType;
import br.com.ufersa.regasync.sensor.repository.SensorReadingRepository;
import br.com.ufersa.regasync.sensor.repository.SensorRepository;
import br.com.ufersa.regasync.sensor.service.SensorService;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SensorFlowTests extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired SensorService service;

    @BeforeEach
    void clearSensors() {
        readings.deleteAll();
        sensors.deleteAll();
    }

    @Test
    @WithMockUser(username = "operador@example.com", roles = "USER")
    void authenticatedUsersCanViewSensorsButCannotManageThem() throws Exception {
        sensors.saveAndFlush(moisture("Umidade canteiro", 1));

        String dashboard = mvc.perform(get("/sensores"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains("Sensores cadastrados", "<table");
        assertThat(dashboard).doesNotContain("Acionamentos da bomba", "Evolução ·");
        mvc.perform(get("/admin/sensores/novo"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/sensores").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void administratorsCanCreateSensorsAndTopicsAreAssignedByTypeAndNumber() throws Exception {
        String form = mvc.perform(get("/admin/sensores/novo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("data-calibration-panel=\"UMIDADE\"", "data-calibration-panel=\"FLUXO\"");
        mvc.perform(post("/admin/sensores").with(csrf())
                        .param("name", "Umidade canteiro")
                        .param("type", "UMIDADE")
                        .param("number", "4")
                        .param("dryAdc", "3000")
                        .param("wetAdc", "1000"))
                .andExpect(status().is3xxRedirection());

        assertThat(sensors.findByTypeAndNumber(SensorType.UMIDADE, 4))
                .get().extracting(Sensor::topic).isEqualTo("/umidade/4");
    }

    @Test
    @WithMockUser(username = "operador@example.com", roles = "USER")
    void methodSecurityProtectsSensorCreationEvenOutsideTheController() {
        SensorForm form = moistureForm(2);
        assertThatThrownBy(() -> service.create(form)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "operador@example.com", roles = "USER")
    void searchRunsInTheDatabaseAndHonorsFilterSortAndPage() {
        sensors.saveAndFlush(moisture("Setor norte", 1));
        sensors.saveAndFlush(moisture("Setor sul", 2));
        sensors.saveAndFlush(moisture("Fluxo principal", 1, SensorType.FLUXO));

        var page = service.search("setor", SensorType.UMIDADE, true, 0, 1, "name", "desc");

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Sensor::getName).containsExactly("Setor sul");
        assertThat(service.search("setor", SensorType.UMIDADE, true, 1, 1, "name", "desc")
                .getContent()).extracting(Sensor::getName).containsExactly("Setor norte");
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void moistureIngestionDeduplicatesMessagesAndPreservesValuesWhenCalibrationChanges() {
        Sensor sensor = sensors.saveAndFlush(moisture("Umidade canteiro", 1));
        String message = "{\"id\":\"boot-1:42\",\"medidoEm\":\"2026-11-05T12:00:00Z\",\"adc\":2000}";

        assertThat(service.ingest("/umidade/1", message)).isTrue();
        assertThat(service.ingest("/umidade/1", message)).isFalse();
        SensorReading first = readings.findFirstBySensorIdOrderByMeasuredAtDescIdDesc(sensor.getId()).orElseThrow();
        assertThat(first.getConvertedValue()).isEqualByComparingTo("50.0");
        assertThat(first.getUnit()).isEqualTo("%");

        SensorForm recalibrated = moistureForm(1);
        recalibrated.setDryAdc(3200);
        service.update(sensor.getId(), recalibrated);
        assertThat(sensors.findById(sensor.getId()).orElseThrow().getCalibrationVersion()).isEqualTo(2);
        assertThat(readings.findBySensorIdAndMeasuredAtBetween(sensor.getId(), Instant.EPOCH,
                Instant.parse("2027-01-01T00:00:00Z"), org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent()).extracting(SensorReading::getConvertedValue).containsExactly(first.getConvertedValue());
    }

    @Test
    @WithMockUser(username = "operador@example.com", roles = "USER")
    void flowIngestionConvertsPulseCountToLitersPerMinute() {
        sensors.saveAndFlush(moisture("Fluxo principal", 1, SensorType.FLUXO));
        String message = "{\"id\":\"boot-1:8\",\"medidoEm\":\"2026-11-05T12:00:00Z\",\"pulsos\":450,\"intervaloMs\":1000}";

        assertThat(service.ingest("/fluxo/1", message)).isTrue();
        assertThat(readings.findAll()).singleElement()
                .extracting(SensorReading::getConvertedValue).isEqualTo(new BigDecimal("27.0000"));
    }

    private Sensor moisture(String name, int number) {
        return moisture(name, number, SensorType.UMIDADE);
    }

    private Sensor moisture(String name, int number, SensorType type) {
        return type == SensorType.UMIDADE
                ? new Sensor(name, type, number, 3000, 1000, null)
                : new Sensor(name, type, number, null, null, new BigDecimal("1000.0000"));
    }

    private SensorForm moistureForm(int number) {
        SensorForm form = new SensorForm();
        form.setName("Umidade canteiro");
        form.setType(SensorType.UMIDADE);
        form.setNumber(number);
        form.setDryAdc(3000);
        form.setWetAdc(1000);
        form.setActive(true);
        return form;
    }
}
