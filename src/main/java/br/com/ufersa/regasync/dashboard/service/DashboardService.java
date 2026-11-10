package br.com.ufersa.regasync.dashboard.service;

import br.com.ufersa.regasync.pump.model.PumpEvent;
import br.com.ufersa.regasync.pump.service.PumpService;
import br.com.ufersa.regasync.sensor.model.*;
import br.com.ufersa.regasync.sensor.repository.SensorRepository;
import br.com.ufersa.regasync.sensor.service.*;
import java.util.Map;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private final SensorRepository repository;
    private final SensorService sensors;
    private final SensorChartService charts;
    private final PumpService pump;
    private final WaterConsumptionService water;
    public DashboardService(SensorRepository repository, SensorService sensors, SensorChartService charts, PumpService pump,WaterConsumptionService water) {
        this.repository=repository;this.sensors=sensors;this.charts=charts;this.pump=pump;this.water=water;
    }
    public record Overview(long totalSensors, long activeSensors, long humiditySensors, long flowSensors,
                           Page<Sensor> sensors, Map<Long, SensorChartService.Chart> charts,
                           Map<Long, SensorReading> latestReadings, PumpEvent pumpEvent,WaterConsumptionService.Consumption consumption) {}
    @Transactional(readOnly=true)
    public Overview overview(int page,ReportPeriod period) {
        var monitored=sensors.search(null,null,true,page,6,"name","asc");
        var ids=monitored.getContent().stream().map(Sensor::getId).toList();
        return new Overview(repository.count(),repository.countByActive(true),
                repository.countByTypeAndActive(SensorType.UMIDADE,true),
                repository.countByTypeAndActive(SensorType.FLUXO,true),
                monitored,charts.charts(ids,period.start(),period.end()),sensors.latestFor(ids),pump.latest().orElse(null),water.consumption(period));
    }
}
