package br.com.ufersa.regasync.sensor.form;

import br.com.ufersa.regasync.sensor.model.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SensorForm {
    @NotBlank @Size(max=120) private String name;
    @NotNull private SensorType type;
    @NotNull @Positive private Integer number;
    private Integer dryAdc;
    private Integer wetAdc;
    @DecimalMin("0.0001") @Digits(integer=8,fraction=4) private BigDecimal pulsesPerLiter;
    private boolean active=true;
    public void setName(String name) { this.name=name==null?null:name.strip(); }
    @AssertTrue(message="Informe ADC seco/molhado diferentes para umidade, ou pulsos por litro para fluxo.")
    public boolean isCalibrationValid() {
        if(type==null) return true;
        return type==SensorType.UMIDADE ? dryAdc!=null && wetAdc!=null && dryAdc>=0 && wetAdc>=0 && !dryAdc.equals(wetAdc) && pulsesPerLiter==null
                : pulsesPerLiter!=null && pulsesPerLiter.signum()>0 && dryAdc==null && wetAdc==null;
    }
    public void populate(Sensor s) {
        name=s.getName(); type=s.getType(); number=s.getNumber(); dryAdc=s.getDryAdc(); wetAdc=s.getWetAdc(); pulsesPerLiter=s.getPulsesPerLiter(); active=s.isActive();
    }
}
