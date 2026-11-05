#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Execute com sudo: sudo sh scripts/install-renewal-timer.sh" >&2
  exit 1
fi

project_dir=$(pwd)
case "$project_dir" in
  *' '*) echo "Mova o projeto para um caminho sem espaços antes de instalar o timer." >&2; exit 1 ;;
esac

cat > /etc/systemd/system/regasync-cert-renew.service <<EOF
[Unit]
Description=Renova os certificados HTTPS e MQTT do RegaSync
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=$project_dir
ExecStart=/bin/sh $project_dir/scripts/renew-certificates.sh
EOF

cat > /etc/systemd/system/regasync-cert-renew.timer <<'EOF'
[Unit]
Description=Verifica a renovação dos certificados RegaSync a cada 12 horas

[Timer]
OnBootSec=5min
OnUnitActiveSec=12h
Persistent=true
Unit=regasync-cert-renew.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now regasync-cert-renew.timer
systemctl status --no-pager regasync-cert-renew.timer
