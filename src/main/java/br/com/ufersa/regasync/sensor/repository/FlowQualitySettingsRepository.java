package br.com.ufersa.regasync.sensor.repository;

import br.com.ufersa.regasync.sensor.model.FlowQualitySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowQualitySettingsRepository extends JpaRepository<FlowQualitySettings,Integer> {}
