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
compose() {
  docker compose -f compose.yaml -f compose.prod.yaml "$@"
}

if compose --profile certificates run --rm --entrypoint /bin/sh certbot \
  -c '[ -s "/etc/letsencrypt/live/$PUBLIC_IP/fullchain.pem" ]'; then
  certificate_exists=true
  if [ ! -f nginx/active.conf ]; then
    envsubst '${PUBLIC_IP}' < nginx/site.conf.template > nginx/active.conf
  fi
else
  certificate_exists=false
  cp nginx/acme-bootstrap.conf nginx/active.conf
fi

compose up -d db mosquitto nginx
compose exec -T nginx nginx -t

if [ "$certificate_exists" = false ]; then
  compose --profile certificates run --rm certbot
  compose --profile certificates run --rm --entrypoint /bin/sh certbot \
    /publish-certificate.sh "/etc/letsencrypt/live/$PUBLIC_IP"
fi

envsubst '${PUBLIC_IP}' < nginx/site.conf.template > nginx/active.conf
compose up -d --build --force-recreate mosquitto
compose up -d --build
compose exec -T nginx nginx -s reload
