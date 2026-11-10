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
    public WaterConsumptionService(SensorReadingRepository readings,ManualIrrigationRepository manual,
                                   @Value("${app.mqtt.device-username:esp32}") String device) {
        this.readings=readings;this.manual=manual;this.device=device;
    }
    public record Consumption(BigDecimal automatic,BigDecimal manual,BigDecimal total,
                              BigDecimal automaticPercent,BigDecimal manualPercent,boolean incomplete) {}
    @Transactional(readOnly=true)
    public Consumption consumption(ReportPeriod period) {
        BigDecimal measured=readings.totalLiters(period.start(),period.end());
        BigDecimal hand=manual.totalLiters(device,period.start(),period.end());
        BigDecimal automatic=measured.subtract(hand).max(BigDecimal.ZERO);
        BigDecimal total=automatic.add(hand);
        BigDecimal autoPercent=total.signum()==0?BigDecimal.ZERO:automatic.multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP);
        BigDecimal manualPercent=total.signum()==0?BigDecimal.ZERO:BigDecimal.valueOf(100).subtract(autoPercent);
        return new Consumption(automatic,hand,total,autoPercent,manualPercent,hand.compareTo(measured)>0);
    }
}
