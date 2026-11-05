package br.com.ufersa.regasync.sensor.model;

public enum SensorType {
    UMIDADE("umidade", "%"), FLUXO("fluxo", "L/min");
    private final String prefix;
    private final String unit;
    SensorType(String prefix, String unit) { this.prefix = prefix; this.unit = unit; }
    public String topic(int number) { return "/" + prefix + "/" + number; }
    public String prefix() { return prefix; }
    public String unit() { return unit; }
}
