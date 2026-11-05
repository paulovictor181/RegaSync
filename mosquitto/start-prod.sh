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
topic read \$SYS/broker/version
user $MQTT_ESP_USERNAME
topic write /umidade/+
topic write /fluxo/+
EOF
chown 1883:1883 /mosquitto/config/passwd /mosquitto/config/acl
chmod 640 /mosquitto/config/passwd /mosquitto/config/acl
if [ -s /mosquitto/certs/fullchain.pem ] && [ -s /mosquitto/certs/privkey.pem ]; then
  cp /bootstrap/mosquitto.prod.conf /mosquitto/config/mosquitto.conf
else
  cp /bootstrap/mosquitto.conf /mosquitto/config/mosquitto.conf
fi
exec mosquitto -c /mosquitto/config/mosquitto.conf
