package br.com.ufersa.regasync.shared.model;

import br.com.ufersa.regasync.shared.ui.BrazilianDates;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record ReportPeriod(LocalDate from, LocalDate to) {
    public ReportPeriod {
        if(from==null || to==null || from.isAfter(to) || from.getYear()<2000 || to.getYear()>2100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um período válido entre 2000 e 2100.");
    }
    public static ReportPeriod resolve(LocalDate from,LocalDate to,int days) {
        LocalDate today=LocalDate.now(BrazilianDates.ZONE);
        return new ReportPeriod(from==null?today.minusDays(days):from,to==null?today:to);
    }
    public Instant start() { return from.atStartOfDay(BrazilianDates.ZONE).toInstant(); }
    public Instant end() { return to.plusDays(1).atStartOfDay(BrazilianDates.ZONE).toInstant(); }
    public String label() { return from.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" a "+to.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")); }
}
