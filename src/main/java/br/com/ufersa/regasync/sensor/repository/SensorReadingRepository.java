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

    interface HourlyAverage {
        Long getSensorId();
        Instant getMeasuredAt();
        BigDecimal getValue();
        long getSamples();
    }
    @Query(value="""
            select sensor_id as sensorId, date_trunc('hour', measured_at at time zone 'UTC') at time zone 'UTC' as measuredAt,
                   avg(converted_value) as value, count(*) as samples
            from sensor_readings where sensor_id in (:ids) and measured_at >= :from and measured_at <= :to
            group by sensor_id, date_trunc('hour', measured_at at time zone 'UTC')
            order by sensor_id, measuredAt
            """, nativeQuery=true)
    List<HourlyAverage> hourlyAverages(List<Long> ids, Instant from, Instant to);
    Page<SensorReading> findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThan(long sensorId,Instant from,Instant to,Pageable pageable);
    boolean existsBySensorIdAndMessageId(long sensorId,String messageId);
}
