#!/bin/sh
set -eu
docker compose -f compose.yaml -f compose.prod.yaml --profile certificates run --rm --entrypoint certbot certbot renew --deploy-hook "/bin/sh /publish-certificate.sh \$RENEWED_LINEAGE"
docker compose -f compose.yaml -f compose.prod.yaml exec -T nginx nginx -s reload
docker compose -f compose.yaml -f compose.prod.yaml exec -T mosquitto kill -HUP 1
