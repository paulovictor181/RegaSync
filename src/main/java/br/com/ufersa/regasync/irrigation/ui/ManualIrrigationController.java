package br.com.ufersa.regasync.irrigation.ui;

import br.com.ufersa.regasync.irrigation.service.ManualIrrigationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ManualIrrigationController {
    private final ManualIrrigationService service;
    public ManualIrrigationController(ManualIrrigationService service) { this.service = service; }
    @GetMapping("/sensores/{sensorId}/rega-manual")
    public String page(@PathVariable long sensorId, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("sensor", service.flowSensor(sensorId));
        model.addAttribute("manual", service.view(sensorId));
        model.addAttribute("history", service.history(sensorId, page));
        return "irrigation/manual";
    }
    @PostMapping("/sensores/{sensorId}/rega-manual/iniciar")
    public String start(@PathVariable long sensorId, RedirectAttributes flash) {
        try {
            service.start(sensorId);
            flash.addFlashAttribute("successMessage", "Pausa solicitada. Aguarde a confirmação do ESP32 antes de abrir o ramal manual.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            flash.addFlashAttribute("errorMessage", e instanceof IllegalArgumentException ? e.getMessage() : "Já existe uma rega manual ativa neste ESP32.");
        }
        return "redirect:/sensores/" + sensorId + "/rega-manual";
    }
    @PostMapping("/sensores/{sensorId}/rega-manual/{sessionId}/finalizar")
    public String finish(@PathVariable long sensorId, @PathVariable long sessionId) {
        service.finish(sensorId, sessionId);
        return "redirect:/sensores/" + sensorId + "/rega-manual";
    }
    @GetMapping("/api/regas-manuais/{sensorId}") @ResponseBody
    public ManualIrrigationService.View state(@PathVariable long sensorId) { return service.view(sensorId); }
}
