package br.com.ufersa.regasync.sensor.repository;

import br.com.ufersa.regasync.sensor.model.SensorReading;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorReadingRepository extends JpaRepository<SensorReading,Long> {
    Optional<SensorReading> findFirstBySensorIdOrderByMeasuredAtDescIdDesc(long sensorId);
    Page<SensorReading> findBySensorIdAndMeasuredAtBetween(long sensorId,Instant from,Instant to,Pageable pageable);
    List<SensorReading> findBySensorIdInOrderBySensorIdAscMeasuredAtDesc(List<Long> sensorIds);
    boolean existsBySensorIdAndMessageId(long sensorId,String messageId);
}
