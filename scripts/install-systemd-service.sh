#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer as root." >&2
  exit 1
fi

project_root=${1:-/opt/pinkward/showdown-backend}
unit_source="$project_root/infra/systemd/gyms-lol.service"
environment_file="$project_root/infra/production.env"

test -f "$unit_source"
test -f "$environment_file"
test "$(stat -c '%a' "$environment_file")" = "600"
/usr/bin/docker compose \
  --project-directory "$project_root/infra" \
  --env-file "$environment_file" \
  -f "$project_root/infra/compose.yml" \
  -f "$project_root/infra/compose.production.yml" \
  config --quiet

install -m 0644 "$unit_source" /etc/systemd/system/gyms-lol.service
systemctl daemon-reload
systemctl enable --now gyms-lol.service
systemctl --no-pager --full status gyms-lol.service
