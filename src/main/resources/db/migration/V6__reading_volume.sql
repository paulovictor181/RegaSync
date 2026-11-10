ALTER TABLE sensor_readings ADD COLUMN volume_liters NUMERIC(24,8);
UPDATE sensor_readings SET volume_liters = converted_value * interval_ms / 60000.0 WHERE interval_ms IS NOT NULL;
ALTER TABLE sensor_readings ADD CONSTRAINT ck_reading_volume CHECK (volume_liters IS NULL OR volume_liters >= 0);
