package br.com.ufersa.regasync.irrigation.repository;

import br.com.ufersa.regasync.irrigation.model.ManualIrrigation;
import br.com.ufersa.regasync.irrigation.model.ManualIrrigation.Status;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ManualIrrigationRepository extends JpaRepository<ManualIrrigation, Long> {
    interface ManualTotals {
        java.math.BigDecimal getLiters();
        long getExcludedSessions();
    }
    @Query(value="""
            with sessions as (
                select liters, started_at,coalesce(measured_end_at,feedback_at) as ended,
                    liters*60/nullif(extract(epoch from coalesce(measured_end_at,feedback_at)-started_at),0) as rate
                from manual_irrigations where device_username=:device and started_at is not null
                    and coalesce(measured_end_at,feedback_at)>=:from and started_at<:to
            ), assessed as (
                select *, (liters>0 and (rate is null or rate<:minimum or rate>:maximum)) as suspect,
                    case when ended=started_at then case when started_at>=:from and started_at<:to then liters else 0 end
                    else liters*greatest(0,extract(epoch from least(ended,cast(:to as timestamptz))-greatest(started_at,cast(:from as timestamptz))))
                        /nullif(extract(epoch from ended-started_at),0) end as volume
                from sessions
            )
            select coalesce(sum(case when not suspect then volume else 0 end),0) as liters,
                count(*) filter(where suspect and volume>0) as excludedSessions from assessed
            """,nativeQuery=true)
    ManualTotals consumption(String device,java.time.Instant from,java.time.Instant to,
                             java.math.BigDecimal minimum,java.math.BigDecimal maximum);

    Optional<ManualIrrigation> findFirstByDeviceUsernameAndStatusNotOrderByIdDesc(String device, Status status);
    Optional<ManualIrrigation> findFirstBySensorIdAndOwnerEmailOrderByIdDesc(long sensor, String owner);
    Page<ManualIrrigation> findBySensorIdAndOwnerEmail(long sensor, String owner, Pageable pageable);
    Page<ManualIrrigation> findBySensorId(long sensor, Pageable pageable);
    List<ManualIrrigation> findByStatusNot(Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ManualIrrigation m where m.id = :id")
    Optional<ManualIrrigation> lockById(long id);
}
