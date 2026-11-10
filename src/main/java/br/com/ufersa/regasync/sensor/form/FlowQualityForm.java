package br.com.ufersa.regasync.sensor.form;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FlowQualityForm {
    @NotNull(message="Informe a vazão mínima.") @DecimalMin(value="0",message="A vazão mínima deve ser zero ou maior.")
    @Digits(integer=8,fraction=4,message="Use até 8 dígitos inteiros e 4 casas decimais.") private BigDecimal minimum;
    @NotNull(message="Informe a vazão máxima.") @DecimalMin(value="0",inclusive=false,message="A vazão máxima deve ser positiva.")
    @Digits(integer=8,fraction=4,message="Use até 8 dígitos inteiros e 4 casas decimais.") private BigDecimal maximum;
    @AssertTrue(message="A vazão máxima deve ser maior que a mínima.")
    public boolean isRangeValid() { return minimum==null || maximum==null || maximum.compareTo(minimum)>0; }
}
