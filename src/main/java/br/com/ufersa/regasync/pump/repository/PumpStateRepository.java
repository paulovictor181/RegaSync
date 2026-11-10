package br.com.ufersa.regasync.pump.repository;

import br.com.ufersa.regasync.pump.model.PumpState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface PumpStateRepository extends JpaRepository<PumpState,String> {
    @Modifying @Query(value="insert into pump_states(device,running) values (:device,false) on conflict do nothing",nativeQuery=true)
    void initialize(String device);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PumpState p where p.device=:device")
    PumpState lock(String device);
}
