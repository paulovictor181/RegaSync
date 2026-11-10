CREATE TABLE flow_quality_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    minimum_flow NUMERIC(12,4) NOT NULL CHECK (minimum_flow >= 0),
    maximum_flow NUMERIC(12,4) NOT NULL CHECK (maximum_flow > minimum_flow),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
