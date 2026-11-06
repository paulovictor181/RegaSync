package br.com.ufersa.regasync.irrigation.repository;

import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation.Status;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ManualIrrigationRepository extends JpaRepository<ManualIrrigation, Long> {
    Optional<ManualIrrigation> findFirstByDeviceUsernameAndStatusNotOrderByIdDesc(String device, Status status);
    Optional<ManualIrrigation> findFirstBySensorIdAndOwnerEmailOrderByIdDesc(long sensor, String owner);
    Page<ManualIrrigation> findBySensorIdAndOwnerEmail(long sensor, String owner, Pageable pageable);
    Page<ManualIrrigation> findBySensorId(long sensor, Pageable pageable);
    List<ManualIrrigation> findByStatusNot(Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ManualIrrigation m where m.id = :id")
    Optional<ManualIrrigation> lockById(long id);
}
