package br.com.ufersa.regasync.sensor.service;

import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ReadingMessage {
    private static ObjectMapper mapper;
    public ReadingMessage(ObjectMapper objectMapper) { mapper=objectMapper; }
    public record Data(String id, Instant medidoEm, Long adc, Long pulsos, Long intervaloMs) { }
    public static Data parse(String json) {
        try {
            Data d=mapper.readValue(json,Data.class);
            if(d.id()==null || d.id().isBlank() || d.id().length()>128 || d.medidoEm()==null) return null;
            if(d.adc()!=null && d.adc()>=0 && d.pulsos()==null && d.intervaloMs()==null) return d;
            if(d.adc()==null && d.pulsos()!=null && d.pulsos()>=0) return new Data(d.id(),d.medidoEm(),null,d.pulsos(),d.intervaloMs());
            return null;
        } catch(Exception e) { return null; }
    }
}
