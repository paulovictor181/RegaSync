package br.com.ufersa.regasync.sensor.mqtt;

import br.com.ufersa.regasync.sensor.model.SensorType;
import br.com.ufersa.regasync.sensor.service.SensorService;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import br.com.ufersa.regasync.irrigation.mqtt.ManualFeedback;
import org.springframework.context.ApplicationEventPublisher;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.mqtt.enabled",havingValue="true")
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConsumer {
    private final IMqttAsyncClient client;
    public MqttConsumer(MqttProperties props,SensorService service,ApplicationEventPublisher events) throws MqttException {
        client=new MqttAsyncClient(props.url(),"regasync-server-"+UUID.randomUUID(),new MemoryPersistence());
        MqttConnectOptions options=new MqttConnectOptions();options.setAutomaticReconnect(true);options.setCleanSession(false);options.setConnectionTimeout(15);options.setKeepAliveInterval(30);
        if(!props.username().isBlank()){options.setUserName(props.username());options.setPassword(props.password().toCharArray());}
        client.setCallback(new MqttCallbackExtended(){
            public void connectComplete(boolean reconnect,String serverURI){try{for(SensorType t:SensorType.values())client.subscribe("/"+t.prefix()+"/+",1);client.subscribe("/regasync/dispositivos/+/rega-manual/estado",1);client.subscribe("/regasync/dispositivos/+/estado",1);}catch(MqttException e){throw new IllegalStateException("Falha ao assinar tópicos MQTT",e);}}
            public void connectionLost(Throwable cause) { }
            public void messageArrived(String topic,MqttMessage msg) throws Exception {
                String payload = new String(msg.getPayload(),java.nio.charset.StandardCharsets.UTF_8);
                if (topic.endsWith("/rega-manual/estado")) events.publishEvent(new ManualFeedback(topic,payload));
                else if (topic.startsWith("/regasync/dispositivos/") && topic.endsWith("/estado")) events.publishEvent(new DeviceFeedback(topic,payload));
                else service.ingest(topic,payload);
            }
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });client.connect(options).waitForCompletion();
    }
    public void publishRetained(String topic,String payload) throws MqttException {
        if (!client.isConnected()) throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        client.publish(topic,payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),1,true);
    }
    @PreDestroy public void close() throws MqttException {if(client.isConnected())client.disconnect().waitForCompletion();client.close();}
}
