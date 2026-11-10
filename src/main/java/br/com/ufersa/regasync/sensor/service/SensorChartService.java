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
    public SensorChartService(SensorReadingRepository readings) { this.readings = readings; }
    public record Point(double x, double y, String label) {}
    public record Chart(String path, List<Point> points, String maximum, long samples) {}

    @Transactional(readOnly=true)
    public Map<Long, Chart> charts(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 50) throw new IllegalArgumentException("Até 50 sensores por consulta.");
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(24));
        Map<Long, List<SensorReadingRepository.HourlyAverage>> groups = new HashMap<>();
        readings.hourlyAverages(ids, start, end).forEach(row ->
                groups.computeIfAbsent(row.getSensorId(), key -> new ArrayList<>()).add(row));
        Map<Long, Chart> result = new HashMap<>();
        var dates = new BrazilianDates();
        groups.forEach((id, rows) -> {
            double maximum = Math.max(1, rows.stream().mapToDouble(row -> row.getValue().doubleValue()).max().orElse(1));
            List<Point> points = new ArrayList<>();
            StringBuilder path = new StringBuilder();
            Instant previous = null;
            long samples = 0;
            for (var row : rows) {
                double x = 40 + Math.max(0, Duration.between(start, row.getMeasuredAt()).toMillis()) / 86400000d * 540;
                double y = 160 - row.getValue().doubleValue() / maximum * 130;
                boolean gap = previous == null || Duration.between(previous, row.getMeasuredAt()).toMinutes() > 60;
                path.append(String.format(Locale.ROOT, "%s %.2f %.2f ", gap ? "M" : "L", x, y));
                String value = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", row.getValue());
                points.add(new Point(x, y, dates.format(row.getMeasuredAt()) + " · Média: " + value + " · " + row.getSamples() + " leituras"));
                samples += row.getSamples();
                previous = row.getMeasuredAt();
            }
            result.put(id, new Chart(path.toString(), points,
                    String.format(Locale.forLanguageTag("pt-BR"), "%.2f", maximum), samples));
        });
        return result;
    }
}
