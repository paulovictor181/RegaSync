package br.com.ufersa.regasync.shared.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component("brazilianDates")
public class BrazilianDates {
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("pt-BR")).withZone(ZONE);
    public String format(Instant value) { return value == null ? "—" : FORMAT.format(value); }
}
