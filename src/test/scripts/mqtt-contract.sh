#!/bin/sh
set -eu

/bin/sh /bootstrap/start-prod.sh > /tmp/broker.log 2>&1 &
broker_pid=$!
trap 'kill "$broker_pid" 2>/dev/null || true' EXIT INT TERM

ready=false
for attempt in 1 2 3 4 5; do
  if mosquitto_sub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
    -t '$SYS/broker/version' -C 1 -W 1 > /dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then cat /tmp/broker.log; exit 1; fi

config_topic="/regasync/dispositivos/$MQTT_ESP_USERNAME/config"
state_topic="/regasync/dispositivos/$MQTT_ESP_USERNAME/estado"
config='{"versao":1,"umidadeLigar":38,"umidadeDesligar":45,"regaAutomatica":true,"intervaloPublicacaoMs":5000}'
mosquitto_pub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
  -t "$config_topic" -q 1 -r -m "$config"
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" -t "$config_topic" -q 1 -C 1 -W 3)
[ "$received" = "$config" ] || { echo 'FAIL: configuracao retida'; exit 1; }

# Alguns clientes retornam 0 mesmo com PUBACK negativo. Confira o dado retido.
mosquitto_pub -h 127.0.0.1 -V mqttv5 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" \
  -t "$config_topic" -q 1 -r -m '{}' > /dev/null 2>&1 || true
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" -t "$config_topic" -C 1 -W 3)
[ "$received" = "$config" ] || { echo 'FAIL: ESP conseguiu alterar configuracao'; exit 1; }
mosquitto_pub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
  -t '/regasync/dispositivos/outro/config' -q 1 -r -m '{"privado":true}'
received=$(mosquitto_sub -h 127.0.0.1 -V mqttv5 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" \
  -t '/regasync/dispositivos/outro/config' -C 1 -W 2 2>/dev/null || true)
[ -z "$received" ] || { echo 'FAIL: ESP conseguiu ler configuracao de outro dispositivo'; exit 1; }

mosquitto_pub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" \
  -t "$state_topic" -q 1 -r -m '{"online":true,"versao":1}'
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" -t "$state_topic" -q 1 -C 1 -W 3)
[ "$received" = '{"online":true,"versao":1}' ] || { echo 'FAIL: confirmacao de estado'; exit 1; }

mosquitto_pub -h 127.0.0.1 -V mqttv5 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
  -t '/umidade/1' -q 1 -r -m '{}' > /dev/null 2>&1 || true
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
  -t '/umidade/1' -C 1 -W 2 2>/dev/null || true)
[ -z "$received" ] || { echo 'FAIL: servidor conseguiu se passar por sensor'; exit 1; }
manual_command="/regasync/dispositivos/$MQTT_ESP_USERNAME/rega-manual/comando"
manual_state="/regasync/dispositivos/$MQTT_ESP_USERNAME/rega-manual/estado"
manual_payload='{"sessaoId":1,"versao":1,"sensorNumero":1,"acao":"pausar"}'
mosquitto_pub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
  -t "$manual_command" -q 1 -r -m "$manual_payload"
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" -t "$manual_command" -C 1 -W 3)
[ "$received" = "$manual_payload" ] || { echo 'FAIL: comando de pausa nao chegou'; exit 1; }
mosquitto_pub -h 127.0.0.1 -V mqttv5 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" \
  -t "$manual_command" -q 1 -r -m '{"acao":"retomar"}' > /dev/null 2>&1 || true
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" -t "$manual_command" -C 1 -W 3)
[ "$received" = "$manual_payload" ] || { echo 'FAIL: ESP conseguiu liberar a propria pausa'; exit 1; }
mosquitto_pub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" \
  -t "$manual_state" -q 1 -r -m '{"fase":"pausada"}'
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" -t "$manual_state" -C 1 -W 3)
[ "$received" = '{"fase":"pausada"}' ] || { echo 'FAIL: confirmacao manual nao chegou ao servidor'; exit 1; }
humidity_topic="/regasync/dispositivos/$MQTT_ESP_USERNAME/sensores/umidade/1/calibracao"
humidity_payload='{"versao":1,"sensorNumero":1,"adcSeco":3000,"adcMolhado":600}'
mosquitto_pub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
  -t "$humidity_topic" -q 1 -r -m "$humidity_payload"
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" -t "$humidity_topic" -C 1 -W 3)
[ "$received" = "$humidity_payload" ] || { echo 'FAIL: faixa de umidade nao chegou ao ESP'; exit 1; }
mosquitto_pub -h 127.0.0.1 -V mqttv5 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" \
  -t "$humidity_topic" -q 1 -r -m '{}' > /dev/null 2>&1 || true
received=$(mosquitto_sub -h 127.0.0.1 -u "$MQTT_ESP_USERNAME" -P "$MQTT_ESP_PASSWORD" -t "$humidity_topic" -C 1 -W 3)
[ "$received" = "$humidity_payload" ] || { echo 'FAIL: ESP conseguiu alterar a faixa de umidade no broker'; exit 1; }
echo 'PASS: broker inicia sem certificado, calibracao de umidade/configuracoes/comandos retidos chegam ao ESP, confirmacoes chegam ao servidor e ACLs bloqueiam acessos indevidos.'
