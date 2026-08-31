#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

infra_dir="${SHOWDOWN_INFRA_DIR:-/opt/pinkward/showdown-backend/infra}"
state_dir="${SHOWDOWN_BACKUP_STATE_DIR:-/var/lib/gyms-lol-backup}"
retention_days="${BACKUP_RETENTION_DAYS:-30}"
prefix="${S3_PREFIX:-showdown-production}"
compose=(docker compose --env-file production.env -f compose.yml -f compose.production.yml)

require_variable() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required backup setting: ${name}" >&2
    exit 1
  fi
}

for variable in AGE_RECIPIENT S3_ENDPOINT_URL S3_BUCKET AWS_DEFAULT_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  require_variable "${variable}"
done
[[ "${AGE_RECIPIENT}" =~ ^age1[0-9a-z]+$ ]] || { echo 'AGE_RECIPIENT is invalid.' >&2; exit 1; }
[[ "${S3_ENDPOINT_URL}" =~ ^https://[^/]+/?$ ]] || { echo 'S3_ENDPOINT_URL must be an HTTPS origin.' >&2; exit 1; }
[[ "${S3_BUCKET}" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo 'S3_BUCKET is invalid.' >&2; exit 1; }
[[ "${prefix}" =~ ^[A-Za-z0-9][A-Za-z0-9/_.-]{0,127}$ && "${prefix}" != *..* ]] || { echo 'S3_PREFIX is invalid.' >&2; exit 1; }
[[ "${retention_days}" =~ ^[0-9]+$ && "${retention_days}" -ge 1 && "${retention_days}" -le 365 ]] || { echo 'BACKUP_RETENTION_DAYS must be between 1 and 365.' >&2; exit 1; }

for command in age aws docker flock sha256sum tar; do
  command -v "${command}" >/dev/null || { echo "Required command is missing: ${command}" >&2; exit 1; }
done

install -d -m 0700 "${state_dir}"
exec 9>"${state_dir}/backup.lock"
flock -n 9 || { echo 'Another production backup is already running.' >&2; exit 1; }

run_dir="$(mktemp -d "${state_dir}/run.XXXXXXXX")"
cleanup() {
  rm -rf -- "${run_dir}"
}
trap cleanup EXIT

cd "${infra_dir}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

databases=(
  'postgres:matchmaking_service:pinkward_matchmaking:matchmaking'
  'match-postgres:match_service:pinkward_matches:matches'
  'player-postgres:player_service:pinkward_players:players'
  'identity-postgres:identity_service:pinkward_identity:identity'
)

manifest="${run_dir}/manifest-${stamp}.json"
artifacts=()
for spec in "${databases[@]}"; do
  IFS=: read -r service user database label <<<"${spec}"
  dump="${run_dir}/${label}-${stamp}.dump"
  "${compose[@]}" exec -T "${service}" pg_dump --username "${user}" --dbname "${database}" --format custom --compress 9 >"${dump}"
  "${compose[@]}" exec -T "${service}" pg_restore --list <"${dump}" >/dev/null
  artifacts+=("${label}|$(basename "${dump}")|$(stat -c %s "${dump}")|$(sha256sum "${dump}" | awk '{print $1}')")
done

{
  printf '{\n  "createdAt": "%s",\n  "format": "PostgreSQL custom archive",\n  "verified": true,\n  "artifacts": [\n' "${stamp}"
  for index in "${!artifacts[@]}"; do
    IFS='|' read -r label file bytes checksum <<<"${artifacts[$index]}"
    separator=','
    [[ "${index}" -eq $((${#artifacts[@]} - 1)) ]] && separator=''
    printf '    {"database":"%s","file":"%s","bytes":%s,"sha256":"%s"}%s\n' "${label}" "${file}" "${bytes}" "${checksum}" "${separator}"
  done
  printf '  ]\n}\n'
} >"${manifest}"

archive="${run_dir}/showdown-backup-${stamp}.tar"
encrypted="${archive}.age"
checksum_file="${encrypted}.sha256"
tar --create --file "${archive}" --directory "${run_dir}" "$(basename "${manifest}")" $(printf '%s\n' "${artifacts[@]}" | cut -d'|' -f2)
age --recipient "${AGE_RECIPIENT}" --output "${encrypted}" "${archive}"
rm -f -- "${archive}" "${run_dir}"/*.dump "${manifest}"
sha256sum "${encrypted}" | sed "s#${run_dir}/##" >"${checksum_file}"

endpoint="${S3_ENDPOINT_URL%/}"
object_key="${prefix%/}/$(basename "${encrypted}")"
checksum_key="${object_key}.sha256"
aws --endpoint-url "${endpoint}" s3 cp "${encrypted}" "s3://${S3_BUCKET}/${object_key}" --only-show-errors --no-progress
aws --endpoint-url "${endpoint}" s3 cp "${checksum_file}" "s3://${S3_BUCKET}/${checksum_key}" --only-show-errors --no-progress
aws --endpoint-url "${endpoint}" s3api head-object --bucket "${S3_BUCKET}" --key "${object_key}" >/dev/null
aws --endpoint-url "${endpoint}" s3api head-object --bucket "${S3_BUCKET}" --key "${checksum_key}" >/dev/null

cutoff_epoch="$(date -u -d "${retention_days} days ago" +%s)"
while IFS=$'\t' read -r modified key; do
  [[ -n "${modified}" && -n "${key}" ]] || continue
  [[ "${key}" == "${prefix%/}/"* ]] || continue
  relative_key="${key#${prefix%/}/}"
  [[ "${relative_key}" =~ ^showdown-backup-[0-9]{8}T[0-9]{6}Z\.tar\.age(\.sha256)?$ ]] || continue
  if [[ "$(date -u -d "${modified}" +%s)" -lt "${cutoff_epoch}" ]]; then
    aws --endpoint-url "${endpoint}" s3api delete-object --bucket "${S3_BUCKET}" --key "${key}" >/dev/null
  fi
done < <(aws --endpoint-url "${endpoint}" s3api list-objects-v2 --bucket "${S3_BUCKET}" --prefix "${prefix%/}/showdown-backup-" --query 'Contents[].[LastModified,Key]' --output text)

echo "Encrypted production backup uploaded and verified: ${object_key}"
