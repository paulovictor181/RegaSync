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

    interface FlowTotals {
        BigDecimal getAutomatic();
        BigDecimal getMeasured();
        BigDecimal getExcludedLiters();
        long getExcludedSamples();
    }
    @Query(value="""
            with samples as (
                select r.*, r.measured_at - r.interval_ms * interval '1 millisecond' as beginning,
                    greatest(0,extract(epoch from least(r.measured_at,cast(:to as timestamptz))
                        - greatest(r.measured_at-r.interval_ms*interval '1 millisecond',cast(:from as timestamptz)))) as seconds,
                    (r.converted_value<0 or r.converted_value>:maximum or (r.converted_value>0 and r.converted_value<:minimum)) as suspect
                from sensor_readings r where r.interval_ms>0 and r.measured_at>=:from
                    and r.measured_at-r.interval_ms*interval '1 millisecond'<:to
            ), allocated as (
                select s.*,least(s.seconds,coalesce((select sum(greatest(0,extract(epoch from
                    least(s.measured_at,coalesce(m.measured_end_at,case when m.status<>'COMPLETED' then greatest(m.feedback_at,current_timestamp) else m.feedback_at end),cast(:to as timestamptz))
                    -greatest(s.beginning,m.started_at,cast(:from as timestamptz)))))
                    from manual_irrigations m where m.sensor_id=s.sensor_id and m.device_username=:device
                        and m.started_at<s.measured_at and coalesce(m.measured_end_at,case when m.status<>'COMPLETED' then greatest(m.feedback_at,current_timestamp) else m.feedback_at end)>s.beginning),0)) as manual_seconds
                from samples s
            )
            select coalesce(sum(case when not suspect then volume_liters*(seconds-manual_seconds)/(interval_ms/1000.0) else 0 end),0) as automatic,
                coalesce(sum(case when not suspect then volume_liters*seconds/(interval_ms/1000.0) else 0 end),0) as measured,
                coalesce(sum(case when suspect then volume_liters*seconds/(interval_ms/1000.0) else 0 end),0) as excludedLiters,
                count(*) filter(where suspect and seconds>0) as excludedSamples
            from allocated
            """,nativeQuery=true)
    FlowTotals flowTotals(Instant from,Instant to,String device,BigDecimal minimum,BigDecimal maximum);

    interface HourlyAverage {
        Long getSensorId();
        Instant getMeasuredAt();
        BigDecimal getValue();
        long getSamples();
        boolean getInterference();
        String getInterferenceReason();
    }
    @Query(value="""
            select sensor_id as sensorId, date_trunc(:bucket, measured_at at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo' as measuredAt,
                   (interval_ms is not null and (converted_value<0 or converted_value>:maximum or (converted_value>0 and converted_value<:minimum))) as interference,
                   case when interval_ms is not null and converted_value>:maximum then 'Vazão acima do limite'
                        when interval_ms is not null and converted_value>0 and converted_value<:minimum then 'Vazão abaixo do limite'
                        when interval_ms is not null and converted_value<0 then 'Vazão negativa' else null end as interferenceReason,
                   avg(converted_value) as value, count(*) as samples
            from sensor_readings where sensor_id in (:ids) and measured_at >= :from and measured_at < :to
            group by 1, 2, 3, 4
            order by sensor_id, measuredAt, interference
            """, nativeQuery=true)
    List<HourlyAverage> hourlyAverages(List<Long> ids, Instant from, Instant to, String bucket, BigDecimal minimum, BigDecimal maximum);
    Page<SensorReading> findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThan(long sensorId,Instant from,Instant to,Pageable pageable);
    boolean existsBySensorIdAndMessageId(long sensorId,String messageId);
}
