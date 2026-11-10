package br.com.ufersa.regasync.dashboard.demo;

import br.com.ufersa.regasync.dashboard.service.WaterConsumptionService.Consumption;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.pump.model.PumpEvent;
import br.com.ufersa.regasync.pump.repository.*;
import br.com.ufersa.regasync.sensor.model.SensorType;
import br.com.ufersa.regasync.sensor.repository.SensorReadingRepository;
import br.com.ufersa.regasync.sensor.service.SensorChartService.*;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import br.com.ufersa.regasync.shared.ui.BrazilianDates;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoDashboardService {
    private final boolean enabled;
    private final SensorReadingRepository readings;
    private final ManualIrrigationRepository manual;
    private final PumpEventRepository events;
    private final PumpStateRepository states;
    public DemoDashboardService(@Value("${app.demo.enabled:false}") boolean enabled,SensorReadingRepository readings,
                                ManualIrrigationRepository manual,PumpEventRepository events,PumpStateRepository states) {
        this.enabled=enabled;this.readings=readings;this.manual=manual;this.events=events;this.states=states;
    }
    public record DemoSensor(long id,String name,SensorType type,boolean active) {}
    public record DemoReading(BigDecimal convertedValue,String unit,Instant measuredAt) {}
    public record Demo(Page<DemoSensor> sensors,Map<Long,Chart> charts,Map<Long,DemoReading> latest,
                       Consumption consumption,PumpEvent pumpEvent) {}
    @Transactional(readOnly=true)
    public boolean available() {
        return enabled && readings.count()==0 && manual.count()==0 && events.count()==0 && states.count()==0;
    }
    @Transactional(readOnly=true)
    public Demo preview(ReportPeriod period) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!available()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Demonstração disponível apenas sem medições, sessões manuais ou eventos da bomba.");
        var sensors=List.of(new DemoSensor(1,"Umidade · demonstração",SensorType.UMIDADE,true),
                new DemoSensor(2,"Fluxo · demonstração",SensorType.FLUXO,true));
        Map<Long,Chart> charts=new HashMap<>();
        Map<Long,DemoReading> latest=new HashMap<>();
        boolean daily=Duration.between(period.start(),period.end()).compareTo(Duration.ofDays(7))>0;
        long days=java.time.temporal.ChronoUnit.DAYS.between(period.from(),period.to())+1;
        int buckets=(int)Math.min(96,daily?days:days*24);
        var dates=new BrazilianDates();
        for(var sensor:sensors) {
            List<Point> points=new ArrayList<>();
            StringBuilder path=new StringBuilder();
            double maximum=sensor.type()==SensorType.UMIDADE?100:0.14;
            for(int i=0;i<buckets;i++) {
                double fraction=buckets==1?0.5:i/(double)(buckets-1);
                Instant at=period.start().plusMillis((long)(Duration.between(period.start(),period.end()).toMillis()*fraction));
                if(!at.isBefore(period.end())) at=period.end().minusSeconds(1);
                double value=sensor.type()==SensorType.UMIDADE?52+14*Math.sin(i*Math.PI/6):100d/1440+0.05*Math.sin(i*Math.PI/6);
                if(daily) value=sensor.type()==SensorType.UMIDADE?52+6*Math.sin(i):100d/1440;
                double x=40+fraction*540,y=160-value/maximum*130;
                path.append(String.format(Locale.ROOT,"%s %.2f %.2f ",i==0?"M":"L",x,y));
                points.add(new Point(x,y,dates.format(at)+" · Média: "+String.format(Locale.forLanguageTag("pt-BR"),"%.2f",value)+" · dados fictícios"));
                latest.put(sensor.id(),new DemoReading(BigDecimal.valueOf(value),sensor.type().unit(),at));
            }
            charts.put(sensor.id(),new Chart(path.toString(),points,String.format(Locale.forLanguageTag("pt-BR"),"%.2f",maximum),buckets));
        }
        BigDecimal total=BigDecimal.valueOf(days*100);
        var consumption=new Consumption(total.multiply(new BigDecimal("0.75")),total.multiply(new BigDecimal("0.25")),total,
                BigDecimal.valueOf(75),BigDecimal.valueOf(25),false);
        return new Demo(new PageImpl<>(sensors),charts,latest,consumption,
                new PumpEvent("demo",false,false,period.end().minusSeconds(3600)));
    }
}
