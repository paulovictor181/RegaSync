#!/bin/sh
set -eu
lineage="${1:?Uso: publish-certificate.sh CAMINHO_DO_CERTIFICADO}"
mkdir -p /mosquitto-certs
cp "$lineage/fullchain.pem" /mosquitto-certs/fullchain.pem.tmp
cp "$lineage/privkey.pem" /mosquitto-certs/privkey.pem.tmp
chmod 644 /mosquitto-certs/fullchain.pem.tmp
chmod 640 /mosquitto-certs/privkey.pem.tmp
mv /mosquitto-certs/fullchain.pem.tmp /mosquitto-certs/fullchain.pem
mv /mosquitto-certs/privkey.pem.tmp /mosquitto-certs/privkey.pem
chown 1883:1883 /mosquitto-certs/privkey.pem
