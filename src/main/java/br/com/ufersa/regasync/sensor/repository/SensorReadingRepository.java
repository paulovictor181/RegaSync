package br.com.ufersa.regasync.sensor.repository;

import br.com.ufersa.regasync.sensor.model.SensorReading;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;

public interface SensorReadingRepository extends JpaRepository<SensorReading,Long> {
    Optional<SensorReading> findFirstBySensorIdOrderByMeasuredAtDescIdDesc(long sensorId);
    Page<SensorReading> findBySensorIdAndMeasuredAtBetween(long sensorId,Instant from,Instant to,Pageable pageable);
    @Query(value="select distinct on (sensor_id) * from sensor_readings where sensor_id in (:sensorIds) order by sensor_id, measured_at desc, id desc", nativeQuery=true)
    List<SensorReading> latestForSensors(List<Long> sensorIds);

    @Query(value="""
            select coalesce(sum(volume_liters * greatest(0, extract(epoch from
                least(measured_at,cast(:to as timestamptz)) - greatest(measured_at - interval_ms * interval '1 millisecond',cast(:from as timestamptz))))
                / (interval_ms / 1000.0)),0)
            from sensor_readings where interval_ms > 0 and measured_at >= :from
                and measured_at - interval_ms * interval '1 millisecond' < :to
            """, nativeQuery=true)
    BigDecimal totalLiters(Instant from, Instant to);

    interface HourlyAverage {
        Long getSensorId();
        Instant getMeasuredAt();
        BigDecimal getValue();
        long getSamples();
    }
    @Query(value="""
            select sensor_id as sensorId, date_trunc(:bucket, measured_at at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo' as measuredAt,
                   avg(converted_value) as value, count(*) as samples
            from sensor_readings where sensor_id in (:ids) and measured_at >= :from and measured_at < :to
            group by 1, 2
            order by sensor_id, measuredAt
            """, nativeQuery=true)
    List<HourlyAverage> hourlyAverages(List<Long> ids, Instant from, Instant to, String bucket);
    Page<SensorReading> findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThan(long sensorId,Instant from,Instant to,Pageable pageable);
    boolean existsBySensorIdAndMessageId(long sensorId,String messageId);
}
