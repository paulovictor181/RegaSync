package br.com.ufersa.regasync.irrigation.repository;

import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation.Status;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ManualIrrigationRepository extends JpaRepository<ManualIrrigation, Long> {
    @Query(value="""
            select coalesce(sum(case when coalesce(measured_end_at,feedback_at)=started_at
                then case when started_at >= :from and started_at < :to then liters else 0 end
                else liters * greatest(0, extract(epoch from least(coalesce(measured_end_at,feedback_at),cast(:to as timestamptz))
                    - greatest(started_at,cast(:from as timestamptz))))
                    / nullif(extract(epoch from coalesce(measured_end_at,feedback_at)-started_at),0) end),0)
            from manual_irrigations where device_username=:device and started_at is not null
                and coalesce(measured_end_at,feedback_at) >= :from and started_at < :to
            """,nativeQuery=true)
    java.math.BigDecimal totalLiters(String device, java.time.Instant from, java.time.Instant to);

    Optional<ManualIrrigation> findFirstByDeviceUsernameAndStatusNotOrderByIdDesc(String device, Status status);
    Optional<ManualIrrigation> findFirstBySensorIdAndOwnerEmailOrderByIdDesc(long sensor, String owner);
    Page<ManualIrrigation> findBySensorIdAndOwnerEmail(long sensor, String owner, Pageable pageable);
    Page<ManualIrrigation> findBySensorId(long sensor, Pageable pageable);
    List<ManualIrrigation> findByStatusNot(Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ManualIrrigation m where m.id = :id")
    Optional<ManualIrrigation> lockById(long id);
}
