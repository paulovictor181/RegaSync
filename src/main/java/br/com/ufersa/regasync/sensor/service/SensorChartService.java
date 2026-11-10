package br.com.ufersa.regasync.sensor.service;

import br.com.ufersa.regasync.sensor.repository.SensorReadingRepository;
import br.com.ufersa.regasync.shared.ui.BrazilianDates;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SensorChartService {
    private final SensorReadingRepository readings;
    private final br.com.ufersa.regasync.sensor.config.FlowQualityPolicy quality;
    public SensorChartService(SensorReadingRepository readings,br.com.ufersa.regasync.sensor.config.FlowQualityPolicy quality) { this.readings=readings;this.quality=quality; }
    public record Point(double x, double y, String label,boolean interference) {
        public Point(double x,double y,String label) { this(x,y,label,false); }
    }
    public record Chart(String path, List<Point> points, String maximum, long samples,long interferenceSamples) {
        public Chart(String path,List<Point> points,String maximum,long samples) { this(path,points,maximum,samples,0); }
    }

    @Transactional(readOnly=true)
    public Map<Long, Chart> charts(List<Long> ids) {
        Instant end=Instant.now();
        return charts(ids,end.minus(Duration.ofHours(24)),end);
    }
    @Transactional(readOnly=true)
    public Map<Long, Chart> charts(List<Long> ids,Instant start,Instant end) {
        return charts(ids,start,end,null);
    }
    @Transactional(readOnly=true)
    public Map<Long,Chart> charts(List<Long> ids,Instant start,Instant end,String interval) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 50) throw new IllegalArgumentException("Até 50 sensores por consulta.");
        if(!start.isBefore(end)) throw new IllegalArgumentException("Período inválido.");
        long duration=Duration.between(start,end).toMillis();
        String bucket=interval==null||interval.equals("auto")?(duration>Duration.ofDays(7).toMillis()?"day":"hour"):interval;
        if(!List.of("minute","hour","day").contains(bucket)) throw new IllegalArgumentException("Intervalo inválido.");
        long bucketMs=switch(bucket) {case "minute" -> 60000;case "hour" -> 3600000;default -> 86400000;};
        if(interval!=null && duration/bucketMs>4096) throw new IllegalArgumentException("Selecione um período menor ou um intervalo maior (até 4.096 intervalos).");
        var limits=quality.limits();
        Map<Long, List<SensorReadingRepository.HourlyAverage>> groups = new HashMap<>();
        readings.hourlyAverages(ids, start, end, bucket,limits.minimum(),limits.maximum()).forEach(row ->
                groups.computeIfAbsent(row.getSensorId(), key -> new ArrayList<>()).add(row));
        Map<Long, Chart> result = new HashMap<>();
        var dates = new BrazilianDates();
        groups.forEach((id, rows) -> {
            double maximum = Math.max(1, rows.stream().mapToDouble(row -> row.getValue().doubleValue()).max().orElse(1));
            List<Point> points = new ArrayList<>();
            StringBuilder path = new StringBuilder();
            Instant previous = null;
            long samples = 0;
            long interferenceSamples=0;
            for (var row : rows) {
                double x = 40 + Math.max(0, Duration.between(start, row.getMeasuredAt()).toMillis()) / (double)duration * 540;
                double y = 160 - row.getValue().doubleValue() / maximum * 130;
                boolean gap = previous == null || Duration.between(previous, row.getMeasuredAt()).toMinutes() > (bucket.equals("day")?1500:bucket.equals("hour")?60:1);
                if(!row.getInterference()) path.append(String.format(Locale.ROOT, "%s %.2f %.2f ", gap ? "M" : "L", x, y));
                String value = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", row.getValue());
                points.add(new Point(x, y, dates.format(row.getMeasuredAt()) + " · Média: " + value + " · " + row.getSamples() + " leituras"+(row.getInterference()?" · Possível interferência · "+row.getInterferenceReason()+" · excluída do consumo":""),row.getInterference()));
                samples += row.getSamples();
                if(row.getInterference()) interferenceSamples+=row.getSamples();
                else previous = row.getMeasuredAt();
            }
            result.put(id, new Chart(path.toString(), points,
                    String.format(Locale.forLanguageTag("pt-BR"), "%.2f", maximum), samples,interferenceSamples));
        });
        return result;
    }
}
