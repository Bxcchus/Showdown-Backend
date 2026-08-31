#!/usr/bin/env bash
set -Eeuo pipefail

root="${SHOWDOWN_BACKEND_ROOT:-/opt/pinkward/showdown-backend}"
config="${1:-/etc/gyms-lol/backup.env}"
target_config=/etc/gyms-lol/backup.env

if [[ "${EUID}" -ne 0 ]]; then
  echo 'Run this script as root.' >&2
  exit 1
fi
[[ -f "${config}" ]] || { echo "Backup configuration is missing: ${config}" >&2; exit 1; }
[[ "$(stat -c %U:%G "${config}")" == 'root:root' && "$(stat -c %a "${config}")" == '600' ]] || {
  echo 'Backup configuration must be owned by root:root with mode 600.' >&2
  exit 1
}
for command in age aws docker; do
  command -v "${command}" >/dev/null || { echo "Required command is missing: ${command}" >&2; exit 1; }
done

install -d -m 0700 -o root -g root /var/lib/gyms-lol-backup
install -d -m 0700 -o root -g root /etc/gyms-lol
if [[ "$(readlink -f "${config}")" != "${target_config}" ]]; then
  install -m 0600 -o root -g root "${config}" "${target_config}"
fi
install -m 0750 -o root -g root "${root}/scripts/backup-production.sh" /usr/local/sbin/gyms-lol-backup
install -m 0644 -o root -g root "${root}/infra/systemd/gyms-lol-backup.service" /etc/systemd/system/gyms-lol-backup.service
install -m 0644 -o root -g root "${root}/infra/systemd/gyms-lol-backup.timer" /etc/systemd/system/gyms-lol-backup.timer
systemd-analyze verify /etc/systemd/system/gyms-lol-backup.service /etc/systemd/system/gyms-lol-backup.timer
systemctl daemon-reload
systemctl enable --now gyms-lol-backup.timer
echo 'GYMS.LOL backup timer enabled.'
