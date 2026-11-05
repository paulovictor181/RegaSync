package br.com.ufersa.regasync.sensor.mqtt;

import br.com.ufersa.regasync.sensor.model.SensorType;
import br.com.ufersa.regasync.sensor.service.SensorService;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
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
    public MqttConsumer(MqttProperties props,SensorService service) throws MqttException {
        client=new MqttAsyncClient(props.url(),"regasync-server-"+UUID.randomUUID(),new MemoryPersistence());
        MqttConnectOptions options=new MqttConnectOptions();options.setAutomaticReconnect(true);options.setCleanSession(false);options.setConnectionTimeout(15);options.setKeepAliveInterval(30);
        if(!props.username().isBlank()){options.setUserName(props.username());options.setPassword(props.password().toCharArray());}
        client.setCallback(new MqttCallbackExtended(){
            public void connectComplete(boolean reconnect,String serverURI){try{for(SensorType t:SensorType.values())client.subscribe("/"+t.prefix()+"/+",1);}catch(MqttException e){throw new IllegalStateException("Falha ao assinar tópicos MQTT",e);}}
            public void connectionLost(Throwable cause) { }
            public void messageArrived(String topic,MqttMessage msg) throws Exception {service.ingest(topic,new String(msg.getPayload(),java.nio.charset.StandardCharsets.UTF_8));}
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });client.connect(options).waitForCompletion();
    }
    @PreDestroy public void close() throws MqttException {if(client.isConnected())client.disconnect().waitForCompletion();client.close();}
}
