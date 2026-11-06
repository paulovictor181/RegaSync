package br.com.ufersa.regasync.sensor.mqtt;

import br.com.ufersa.regasync.sensor.service.HumidityCalibrationService;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true")
public class HumidityCalibrationDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(HumidityCalibrationDispatcher.class);
    private final HumidityCalibrationService service;
    private final MqttConsumer mqtt;
    public HumidityCalibrationDispatcher(HumidityCalibrationService service, MqttConsumer mqtt) {
        this.service = service; this.mqtt = mqtt;
    }
    @Scheduled(fixedDelay = 10000, initialDelay = 2000)
    public void dispatch() {
        for (var command : service.desiredCalibrations()) {
            try { mqtt.publishRetained(command.topic(), command.payload()); }
            catch (org.eclipse.paho.client.mqttv3.MqttException e) { LOG.warn("Sem conexão MQTT para sincronizar calibração de umidade; nova tentativa em 10 segundos."); }
        }
    }
    @EventListener
    public void feedback(DeviceFeedback feedback) { service.acknowledge(feedback.topic(), feedback.payload()); }
}
