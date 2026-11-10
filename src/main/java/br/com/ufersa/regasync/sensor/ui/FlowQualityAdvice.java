package br.com.ufersa.regasync.sensor.ui;

import br.com.ufersa.regasync.sensor.config.FlowQualityPolicy;
import br.com.ufersa.regasync.dashboard.ui.DashboardController;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice(assignableTypes={SensorController.class,SensorChartController.class,DashboardController.class})
public class FlowQualityAdvice {
    private final FlowQualityPolicy quality;
    public FlowQualityAdvice(FlowQualityPolicy quality) { this.quality=quality; }
    @ModelAttribute("flowLimits") public FlowQualityPolicy.Limits limits() { return quality.limits(); }
}
