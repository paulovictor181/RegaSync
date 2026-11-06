#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo 'Uso: sh scripts/configure-esp32.sh USUARIO_ESP ARQUIVO_JSON [--prod]' >&2
  exit 1
fi
device_user=$1
config_file=$2
case "$device_user" in
  ''|*[!a-zA-Z0-9_-]*) echo 'Usuario ESP invalido; use letras, numeros, _ ou -.' >&2; exit 1 ;;
esac
[ -f "$config_file" ] || { echo 'Arquivo JSON nao encontrado.' >&2; exit 1; }
[ "$(wc -c < "$config_file")" -le 512 ] || { echo 'JSON deve ter no maximo 512 bytes.' >&2; exit 1; }
case "${3:-}" in
  --prod) production=true ;;
  '') production=false ;;
  *) echo 'Opcao desconhecida. Use --prod para a VPS.' >&2; exit 1 ;;
esac

compose() {
  if [ "$production" = true ]; then
    docker compose -f compose.yaml -f compose.prod.yaml "$@"
  else
    docker compose -f compose.yaml "$@"
  fi
}

compose exec -T mosquitto /bin/sh -c '
  mosquitto_pub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" \
    -t "/regasync/dispositivos/$1/config" -q 1 -r -s
' sh "$device_user" < "$config_file"
echo "Configuracao enviada ao broker. Confira a versao aplicada em /regasync/dispositivos/$device_user/estado."
