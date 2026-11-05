#!/bin/sh
set -eu
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi
[ -n "${PUBLIC_IP:-}" ] || { echo 'Defina PUBLIC_IP no .env'; exit 1; }
[ -n "${ACME_EMAIL:-}" ] || { echo 'Defina ACME_EMAIL no .env'; exit 1; }
[ -n "${MQTT_SERVER_PASSWORD:-}" ] || { echo 'Defina MQTT_SERVER_PASSWORD no .env'; exit 1; }
[ -n "${MQTT_ESP_PASSWORD:-}" ] || { echo 'Defina MQTT_ESP_PASSWORD no .env'; exit 1; }
cp nginx/acme-bootstrap.conf nginx/active.conf
docker compose -f compose.yaml -f compose.prod.yaml up -d db mosquitto nginx
docker compose -f compose.yaml -f compose.prod.yaml --profile certificates run --rm certbot
docker compose -f compose.yaml -f compose.prod.yaml --profile certificates run --rm --entrypoint /bin/sh certbot /publish-certificate.sh "/etc/letsencrypt/live/$PUBLIC_IP"
envsubst '${PUBLIC_IP}' < nginx/site.conf.template > nginx/active.conf
docker compose -f compose.yaml -f compose.prod.yaml up -d --build
docker compose -f compose.yaml -f compose.prod.yaml exec -T nginx nginx -s reload
docker compose -f compose.yaml -f compose.prod.yaml exec -T mosquitto kill -HUP 1
