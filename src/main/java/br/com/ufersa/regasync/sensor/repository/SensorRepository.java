package br.com.ufersa.regasync.sensor.repository;

import br.com.ufersa.regasync.sensor.model.*;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SensorRepository extends JpaRepository<Sensor,Long>, JpaSpecificationExecutor<Sensor> {
    Optional<Sensor> findByTypeAndNumber(SensorType type,int number);
    boolean existsByTypeAndNumber(SensorType type,int number);
    List<Sensor> findByType(SensorType type);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sensor s where s.type = :type and s.number = :number")
    Optional<Sensor> lockByTypeAndNumber(SensorType type, int number);
}
