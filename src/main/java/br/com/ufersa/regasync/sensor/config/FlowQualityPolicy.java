package br.com.ufersa.regasync.sensor.config;

import br.com.ufersa.regasync.sensor.form.FlowQualityForm;
import br.com.ufersa.regasync.sensor.model.FlowQualitySettings;
import br.com.ufersa.regasync.sensor.repository.FlowQualitySettingsRepository;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component("flowQuality")
public class FlowQualityPolicy {
    private final Limits defaults;
    private final FlowQualitySettingsRepository repository;
    private final Validator validator;
    public record Limits(BigDecimal minimum,BigDecimal maximum) {
        public boolean suspect(BigDecimal value) { return value.signum()<0 || value.compareTo(maximum)>0 || (value.signum()>0 && value.compareTo(minimum)<0); }
    }
    public FlowQualityPolicy(@Value("${app.flow-quality.minimum:0.1}") BigDecimal minimum,
                             @Value("${app.flow-quality.maximum:30}") BigDecimal maximum,
                             FlowQualitySettingsRepository repository,Validator validator) {
        if(minimum.signum()<0 || maximum.compareTo(minimum)<=0) throw new IllegalArgumentException("Limites de vazão inválidos.");
        this.defaults=new Limits(minimum,maximum);this.repository=repository;this.validator=validator;
    }
    @Transactional(readOnly=true)
    public Limits limits() { return repository.findById(1).map(s->new Limits(s.getMinimum(),s.getMaximum())).orElse(defaults); }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public void save(FlowQualityForm form) {
        if(!validator.validate(form).isEmpty()) throw new IllegalArgumentException("Informe limites de vazão válidos.");
        repository.saveAndFlush(new FlowQualitySettings(form.getMinimum(),form.getMaximum()));
    }
}
