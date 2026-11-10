package br.com.ufersa.regasync.dashboard.service;

import br.com.ufersa.regasync.sensor.repository.SensorReadingRepository;
import br.com.ufersa.regasync.irrigation.repository.ManualIrrigationRepository;
import br.com.ufersa.regasync.shared.model.ReportPeriod;
import java.math.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaterConsumptionService {
    private final SensorReadingRepository readings;
    private final ManualIrrigationRepository manual;
    private final String device;
    private final br.com.ufersa.regasync.sensor.config.FlowQualityPolicy quality;
    public WaterConsumptionService(SensorReadingRepository readings,ManualIrrigationRepository manual,
                                   @Value("${app.mqtt.device-username:esp32}") String device,br.com.ufersa.regasync.sensor.config.FlowQualityPolicy quality) {
        this.readings=readings;this.manual=manual;this.device=device;this.quality=quality;
    }
    public record Consumption(BigDecimal automatic,BigDecimal manual,BigDecimal total,
                              BigDecimal automaticPercent,BigDecimal manualPercent,boolean incomplete,
                              long excludedSamples,BigDecimal excludedLiters,long excludedManualSessions) {
        public Consumption(BigDecimal automatic,BigDecimal manual,BigDecimal total,BigDecimal automaticPercent,BigDecimal manualPercent,boolean incomplete) {
            this(automatic,manual,total,automaticPercent,manualPercent,incomplete,0,BigDecimal.ZERO,0);
        }
    }
    @Transactional(readOnly=true)
    public Consumption consumption(ReportPeriod period) {
        var limits=quality.limits();
        var flow=readings.flowTotals(period.start(),period.end(),device,limits.minimum(),limits.maximum());
        var sessions=manual.consumption(device,period.start(),period.end(),limits.minimum(),limits.maximum());
        BigDecimal measured=flow.getMeasured();
        BigDecimal hand=sessions.getLiters();
        BigDecimal automatic=flow.getAutomatic().max(BigDecimal.ZERO);
        BigDecimal total=automatic.add(hand);
        BigDecimal autoPercent=total.signum()==0?BigDecimal.ZERO:automatic.multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP);
        BigDecimal manualPercent=total.signum()==0?BigDecimal.ZERO:BigDecimal.valueOf(100).subtract(autoPercent);
        return new Consumption(automatic,hand,total,autoPercent,manualPercent,hand.compareTo(measured)>0,flow.getExcludedSamples(),flow.getExcludedLiters(),sessions.getExcludedSessions());
    }
}
