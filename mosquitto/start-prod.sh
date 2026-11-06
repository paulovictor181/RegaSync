#!/bin/sh
set -eu
umask 077
mkdir -p /mosquitto/config /mosquitto/data
rm -f /mosquitto/config/passwd
mosquitto_passwd -c -b /mosquitto/config/passwd "$MQTT_SERVER_USERNAME" "$MQTT_SERVER_PASSWORD"
mosquitto_passwd -b /mosquitto/config/passwd "$MQTT_ESP_USERNAME" "$MQTT_ESP_PASSWORD"
cat > /mosquitto/config/acl <<EOF
user $MQTT_SERVER_USERNAME
topic read /umidade/+
topic read /fluxo/+
topic write /regasync/dispositivos/+/config
topic read /regasync/dispositivos/+/estado
topic write /regasync/dispositivos/+/sensores/umidade/+/calibracao
topic write /regasync/dispositivos/+/rega-manual/comando
topic read /regasync/dispositivos/+/rega-manual/estado
topic read \$SYS/broker/version
user $MQTT_ESP_USERNAME
topic write /umidade/+
topic write /fluxo/+
topic read /regasync/dispositivos/$MQTT_ESP_USERNAME/config
topic write /regasync/dispositivos/$MQTT_ESP_USERNAME/estado
topic read /regasync/dispositivos/$MQTT_ESP_USERNAME/sensores/umidade/+/calibracao
topic read /regasync/dispositivos/$MQTT_ESP_USERNAME/rega-manual/comando
topic write /regasync/dispositivos/$MQTT_ESP_USERNAME/rega-manual/estado
EOF
chown 1883:1883 /mosquitto/config/passwd /mosquitto/config/acl
chmod 640 /mosquitto/config/passwd /mosquitto/config/acl
if [ -s /mosquitto/certs/fullchain.pem ] && [ -s /mosquitto/certs/privkey.pem ]; then
  cp /bootstrap/mosquitto.prod.conf /mosquitto/config/mosquitto.conf
else
  cat > /mosquitto/config/mosquitto.conf <<'EOF'
listener 1883
allow_anonymous false
password_file /mosquitto/config/passwd
acl_file /mosquitto/config/acl
persistence true
persistence_location /mosquitto/data/
EOF
fi
exec mosquitto -c /mosquitto/config/mosquitto.conf
