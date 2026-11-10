package br.com.ufersa.regasync.dashboard.demo;

import br.com.ufersa.regasync.shared.model.ReportPeriod;
import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class DemoDashboardController {
    private final DemoDashboardService service;
    public DemoDashboardController(DemoDashboardService service) { this.service=service; }
    @GetMapping("/dashboard/demo")
    public String demo(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,Model model) {
        var period=ReportPeriod.resolve(from,to,0);
        var demo=service.preview(period);
        model.addAttribute("demo",true);model.addAttribute("demoAvailable",false);
        model.addAttribute("dashboardPath","/dashboard/demo");
        model.addAttribute("from",period.from());model.addAttribute("to",period.to());
        model.addAttribute("periodLabel",period.label());
        model.addAttribute("dailyChart",Duration.between(period.start(),period.end()).compareTo(Duration.ofDays(7))>0);
        model.addAttribute("consumption",demo.consumption());model.addAttribute("totalSensors",2);
        model.addAttribute("activeSensors",2);model.addAttribute("humiditySensors",1);model.addAttribute("flowSensors",1);
        model.addAttribute("sensors",demo.sensors());model.addAttribute("charts",demo.charts());
        model.addAttribute("latestReadings",demo.latest());model.addAttribute("pumpEvent",demo.pumpEvent());
        return "dashboard/index";
    }
    @GetMapping("/dashboard/demo/grafico/{id}")
    public String chart(@PathVariable long id,
                        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue="auto") String interval,Model model) {
        var period=ReportPeriod.resolve(from,to,0);
        var preview=service.preview(period,interval);
        var sensor=preview.sensors().getContent().stream().filter(s->s.id()==id).findFirst()
                .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        model.addAttribute("sensor",sensor);model.addAttribute("chart",preview.charts().get(id));
        model.addAttribute("demo",true);model.addAttribute("from",period.from());model.addAttribute("to",period.to());
        model.addAttribute("interval",interval);model.addAttribute("periodLabel",period.label());
        boolean daily=interval.equals("day") || (interval.equals("auto") && Duration.between(period.start(),period.end()).compareTo(Duration.ofDays(7))>0);
        model.addAttribute("dailyChart",daily);
        model.addAttribute("chartIntervalLabel",interval.equals("minute")?"Médias por minuto":daily?"Médias por dia":"Médias por hora");
        return "sensors/chart-expanded";
    }

}
