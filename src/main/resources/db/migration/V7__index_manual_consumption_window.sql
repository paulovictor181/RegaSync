CREATE INDEX idx_manual_consumption_window ON manual_irrigations(sensor_id, device_username, started_at)
    WHERE started_at IS NOT NULL;

CREATE INDEX idx_flow_readings_time ON sensor_readings(measured_at) WHERE interval_ms > 0;
