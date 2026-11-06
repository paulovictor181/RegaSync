package br.com.ufersa.regasync.sensor;

import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.*;
import br.com.ufersa.regasync.sensor.service.HumidityCalibrationService;
import br.com.ufersa.regasync.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HumidityCalibrationTests extends IntegrationTestSupport {
    @Autowired SensorRepository sensors;
    @Autowired SensorReadingRepository readings;
    @Autowired HumidityCalibrationService calibration;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    Sensor sensor;
    @BeforeEach void seed() {
        readings.deleteAll(); sensors.deleteAll();
        sensor = sensors.saveAndFlush(new Sensor("Umidade",SensorType.UMIDADE,1,3000,600,null));
    }
    String ack(int version, int dry, int wet) {
        return json.writeValueAsString(Map.of("online",true,"sensorUmidadeNumero",1,"versaoCalibracaoUmidade",version,
                "adcSeco",dry,"adcMolhado",wet,"medidoEm",Instant.now().toString()));
    }
    @Test void commandsContainOnlySavedCalibrationsAndExpectedDeviceAndSensor() {
        assertThat(calibration.desiredCalibrations()).singleElement().satisfies(c -> {
            assertThat(c.topic()).isEqualTo("/regasync/dispositivos/esp32/sensores/umidade/1/calibracao");
            var payload = json.readTree(c.payload());
            assertThat(payload.path("adcSeco").asInt()).isEqualTo(3000);
            assertThat(payload.path("adcMolhado").asInt()).isEqualTo(600);
            assertThat(payload.path("versao").asInt()).isEqualTo(1);
        });
    }
    @Test void onlyMatchingDeviceVersionAndEndpointsConfirmApplication() {
        calibration.acknowledge("/regasync/dispositivos/outro/estado",ack(1,3000,600));
        calibration.acknowledge("/regasync/dispositivos/esp32/estado",ack(0,3000,600));
        calibration.acknowledge("/regasync/dispositivos/esp32/estado",ack(1,4095,1690));
        assertThat(calibration.view(sensor.getId()).confirmada()).isFalse();
        calibration.acknowledge("/regasync/dispositivos/esp32/estado",ack(1,3000,600));
        assertThat(calibration.view(sensor.getId()).confirmada()).isTrue();
        Sensor changed = sensors.findById(sensor.getId()).orElseThrow();
        changed.update("Umidade",3200,700,null,true);
        sensors.saveAndFlush(changed);
        assertThat(calibration.view(sensor.getId()).confirmada()).isFalse();
        calibration.acknowledge("/regasync/dispositivos/esp32/estado",ack(1,3000,600));
        assertThat(calibration.view(sensor.getId()).confirmada()).isFalse();
        calibration.acknowledge("/regasync/dispositivos/esp32/estado",ack(2,3200,700));
        assertThat(calibration.view(sensor.getId()).confirmada()).isTrue();
    }
    @Test @WithMockUser(username="admin@example.com",roles="ADMIN")
    void adminChangesRangeThroughFormAndOutOfRangeValuesAreRejected() throws Exception {
        mvc.perform(post("/admin/sensores/"+sensor.getId()+"/editar").with(csrf())
                .param("name","Umidade").param("type","UMIDADE").param("number","1")
                .param("dryAdc","3200").param("wetAdc","700").param("active","true"))
                .andExpect(status().is3xxRedirection());
        assertThat(calibration.view(sensor.getId()).adcSeco()).isEqualTo(3200);
        assertThat(calibration.view(sensor.getId()).versao()).isEqualTo(2);
        mvc.perform(post("/admin/sensores/"+sensor.getId()+"/editar").with(csrf())
                .param("name","Umidade").param("type","UMIDADE").param("number","1")
                .param("dryAdc","5000").param("wetAdc","700"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("sensorForm","dryAdc"));
        assertThat(calibration.view(sensor.getId()).adcSeco()).isEqualTo(3200);
        mvc.perform(get("/sensores/"+sensor.getId())).andExpect(status().isOk());
    }
    @Test @WithMockUser(username="user@example.com")
    void usersCanSeeConfirmationButCannotChangeCalibration() throws Exception {
        mvc.perform(get("/api/sensores/"+sensor.getId()+"/calibracao"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmada").value(false));
        mvc.perform(post("/admin/sensores/"+sensor.getId()+"/editar").with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/sensores/"+sensor.getId()+"/calibracao").with(anonymous()))
                .andExpect(status().is3xxRedirection());
    }
}
