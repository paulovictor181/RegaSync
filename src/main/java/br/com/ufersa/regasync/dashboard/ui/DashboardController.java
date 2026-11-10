package br.com.ufersa.regasync.dashboard.ui;

import br.com.ufersa.regasync.dashboard.service.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import org.springframework.web.bind.annotation.*;

@Controller
public class DashboardController {
    private final DashboardService service;
    private final br.com.ufersa.regasync.dashboard.demo.DemoDashboardService demo;
    public DashboardController(DashboardService service,br.com.ufersa.regasync.dashboard.demo.DemoDashboardService demo) { this.service=service;this.demo=demo; }
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to, Model model) {
        model.addAttribute("demo",false);model.addAttribute("demoAvailable",demo.available());
        model.addAttribute("dashboardPath","/dashboard");
        var period=ReportPeriod.resolve(from,to,0);
        var overview=service.overview(page,period);
        model.addAttribute("from",period.from());model.addAttribute("to",period.to());
        model.addAttribute("periodLabel",period.label());
        model.addAttribute("dailyChart",java.time.Duration.between(period.start(),period.end()).compareTo(java.time.Duration.ofDays(7))>0);
        model.addAttribute("consumption",overview.consumption());
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
