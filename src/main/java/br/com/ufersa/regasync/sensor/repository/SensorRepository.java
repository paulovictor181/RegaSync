package br.com.ufersa.regasync.sensor.repository;

import br.com.ufersa.regasync.sensor.model.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SensorRepository extends JpaRepository<Sensor,Long>, JpaSpecificationExecutor<Sensor> {
    Optional<Sensor> findByTypeAndNumber(SensorType type,int number);
    boolean existsByTypeAndNumber(SensorType type,int number);
}
