package br.com.ufersa.regasync.irrigation.mqtt;

import br.com.ufersa.regasync.irrigation.service.ManualIrrigationService;
import br.com.ufersa.regasync.sensor.mqtt.MqttConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true")
public class ManualMqttDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ManualMqttDispatcher.class);
    private final ManualIrrigationService service;
    private final MqttConsumer mqtt;
    public ManualMqttDispatcher(ManualIrrigationService service, MqttConsumer mqtt) {
        this.service = service; this.mqtt = mqtt;
    }
    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public void dispatch() {
        for (var command : service.pendingCommands()) {
            try { mqtt.publishRetained(command.topic(), command.payload()); }
            catch (org.eclipse.paho.client.mqttv3.MqttException e) {
                LOG.warn("ESP32 sem conexão para comando de rega manual; nova tentativa em 5 segundos.");
            }
        }
    }
    @EventListener
    public void feedback(ManualFeedback feedback) { service.acknowledge(feedback.topic(), feedback.payload()); }
}
