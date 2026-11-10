package br.com.ufersa.regasync.pump.repository;

import br.com.ufersa.regasync.pump.model.PumpEvent;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PumpEventRepository extends JpaRepository<PumpEvent,Long> {
    Page<PumpEvent> findByDevice(String device, Pageable pageable);
    Optional<PumpEvent> findFirstByDeviceOrderByMeasuredAtDescIdDesc(String device);
}
