package br.com.ufersa.regasync.sensor.ui;

import br.com.ufersa.regasync.sensor.service.HumidityCalibrationService;
import org.springframework.web.bind.annotation.*;

@RestController
public class SensorCalibrationController {
    private final HumidityCalibrationService service;
    public SensorCalibrationController(HumidityCalibrationService service) { this.service = service; }
    @GetMapping("/api/sensores/{id}/calibracao")
    public HumidityCalibrationService.View calibration(@PathVariable long id) { return service.view(id); }
}
