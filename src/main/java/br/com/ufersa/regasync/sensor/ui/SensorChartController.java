package br.com.ufersa.regasync.sensor.ui;

import br.com.ufersa.regasync.sensor.service.*;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import java.time.*;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SensorChartController {
    private final SensorService sensors;
    private final SensorChartService charts;
    public SensorChartController(SensorService sensors,SensorChartService charts) { this.sensors=sensors;this.charts=charts; }
    @GetMapping("/sensores/{id}/grafico")
    public String chart(@PathVariable long id,
                        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
                        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
                        @RequestParam(defaultValue="auto") String interval,Model model) {
        br.com.ufersa.regasync.sensor.model.Sensor sensor;
        try { sensor=sensors.get(id); }
        catch(java.util.NoSuchElementException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND); }
        var period=ReportPeriod.resolve(from,to,0);
        if(startAt==null) startAt=period.from().atStartOfDay();
        if(endAt==null) endAt=period.to().plusDays(1).atStartOfDay();
        if(!startAt.isBefore(endAt)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"O horário inicial deve ser anterior ao final.");
        period=new ReportPeriod(startAt.toLocalDate(),endAt.minusNanos(1).toLocalDate());
        var start=startAt.atZone(br.com.ufersa.regasync.shared.ui.BrazilianDates.ZONE).toInstant();
        var end=endAt.atZone(br.com.ufersa.regasync.shared.ui.BrazilianDates.ZONE).toInstant();
        model.addAttribute("startAt",startAt.toString());model.addAttribute("endAt",endAt.toString());
        model.addAttribute("demo",false);
        model.addAttribute("sensor",sensor);model.addAttribute("from",period.from());model.addAttribute("to",period.to());
        model.addAttribute("periodLabel",startAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))+" a "+endAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));model.addAttribute("interval",interval);
        model.addAttribute("dailyChart",interval.equals("day") || (interval.equals("auto") && Duration.between(start,end).compareTo(Duration.ofDays(7))>0));
        model.addAttribute("chartIntervalLabel",switch(interval) { case "minute" -> "Médias por minuto";case "hour" -> "Médias por hora";case "day" -> "Médias por dia";default -> Duration.between(start,end).compareTo(Duration.ofDays(7))>0?"Médias por dia":"Médias por hora";});
        try { model.addAttribute("chart",charts.charts(List.of(id),start,end,interval).get(id)); }
        catch(IllegalArgumentException e) { model.addAttribute("chartError",e.getMessage()); }
        return "sensors/chart-expanded";
    }
}
