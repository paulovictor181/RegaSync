package br.com.ufersa.regasync.sensor.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mqtt")
public record MqttProperties(boolean enabled,String url,String username,String password) { }
