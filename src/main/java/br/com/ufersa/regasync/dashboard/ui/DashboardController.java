package br.com.ufersa.regasync.dashboard.ui;

import br.com.ufersa.regasync.dashboard.service.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service=service; }
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(defaultValue="0") int page, Model model) {
        var overview=service.overview(page);
        model.addAttribute("totalSensors",overview.totalSensors());
        model.addAttribute("activeSensors",overview.activeSensors());
        model.addAttribute("humiditySensors",overview.humiditySensors());
        model.addAttribute("flowSensors",overview.flowSensors());
        model.addAttribute("sensors",overview.sensors());
        model.addAttribute("charts",overview.charts());
        model.addAttribute("latestReadings",overview.latestReadings());
        model.addAttribute("pumpEvent",overview.pumpEvent());
        return "dashboard/index";
    }
}
