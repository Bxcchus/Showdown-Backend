#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

if [[ "$#" -lt 1 || "$#" -gt 2 ]]; then
  echo "Usage: $0 AGE_IDENTITY_FILE [S3_OBJECT_KEY]" >&2
  exit 2
fi

identity_file="$1"
requested_key="${2:-}"
prefix="${S3_PREFIX:-showdown-production}"
image="${SHOWDOWN_POSTGRES_IMAGE:-pinkward/postgres:17.11-hardened}"

for variable in S3_ENDPOINT_URL S3_BUCKET AWS_DEFAULT_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  [[ -n "${!variable:-}" ]] || { echo "Missing required restore setting: ${variable}" >&2; exit 1; }
done
[[ -f "${identity_file}" ]] || { echo 'The age identity file is missing.' >&2; exit 1; }
for command in age aws docker jq openssl sha256sum tar; do
  command -v "${command}" >/dev/null || { echo "Required command is missing: ${command}" >&2; exit 1; }
done

endpoint="${S3_ENDPOINT_URL%/}"
if [[ -z "${requested_key}" ]]; then
  requested_key="$(aws --endpoint-url "${endpoint}" s3api list-objects-v2 --bucket "${S3_BUCKET}" --prefix "${prefix%/}/showdown-backup-" --query 'Contents[].Key' --output text |
    tr '\t' '\n' | grep -E "^${prefix%/}/showdown-backup-[0-9]{8}T[0-9]{6}Z\.tar\.age$" | sort | tail -n 1)"
fi
[[ "${requested_key}" == "${prefix%/}/"* ]] || { echo 'The requested backup key is outside the configured prefix.' >&2; exit 1; }
relative_key="${requested_key#${prefix%/}/}"
[[ "${relative_key}" =~ ^showdown-backup-[0-9]{8}T[0-9]{6}Z\.tar\.age$ ]] || { echo 'The requested backup key is invalid.' >&2; exit 1; }

temporary="$(mktemp -d)"
containers=()
cleanup() {
  for container in "${containers[@]}"; do docker rm --force --volumes "${container}" >/dev/null 2>&1 || true; done
  rm -rf -- "${temporary}"
}
trap cleanup EXIT

encrypted="${temporary}/$(basename "${requested_key}")"
checksum_file="${encrypted}.sha256"
aws --endpoint-url "${endpoint}" s3 cp "s3://${S3_BUCKET}/${requested_key}" "${encrypted}" --only-show-errors --no-progress
aws --endpoint-url "${endpoint}" s3 cp "s3://${S3_BUCKET}/${requested_key}.sha256" "${checksum_file}" --only-show-errors --no-progress
(cd "${temporary}" && sha256sum --check "$(basename "${checksum_file}")")

archive="${temporary}/backup.tar"
age --decrypt --identity "${identity_file}" --output "${archive}" "${encrypted}"
mapfile -t entries < <(tar --list --file "${archive}")
[[ "${#entries[@]}" -eq 5 ]] || { echo 'The archive must contain four dumps and one manifest.' >&2; exit 1; }
for entry in "${entries[@]}"; do
  [[ "${entry}" =~ ^(matchmaking|matches|players|identity)-[0-9]{8}T[0-9]{6}Z\.dump$|^manifest-[0-9]{8}T[0-9]{6}Z\.json$ ]] || { echo "Unsafe archive entry: ${entry}" >&2; exit 1; }
done
tar --extract --file "${archive}" --directory "${temporary}"
manifest="$(find "${temporary}" -maxdepth 1 -type f -name 'manifest-*.json' -print -quit)"
jq --exit-status '.verified == true and (.artifacts | length == 4)' "${manifest}" >/dev/null

while IFS=$'\t' read -r label file expected_checksum; do
  dump="${temporary}/${file}"
  [[ -f "${dump}" ]] || { echo "Missing dump: ${file}" >&2; exit 1; }
  [[ "$(sha256sum "${dump}" | awk '{print $1}')" == "${expected_checksum}" ]] || { echo "Checksum mismatch: ${file}" >&2; exit 1; }

  container="gyms-lol-restore-${label}-$$"
  containers+=("${container}")
  password="$(openssl rand -hex 24)"
  docker run --detach --name "${container}" --network none \
    --env POSTGRES_DB=restore_validation --env POSTGRES_USER=restore_admin --env "POSTGRES_PASSWORD=${password}" \
    "${image}" >/dev/null
  for _ in $(seq 1 60); do
    docker exec "${container}" pg_isready --username restore_admin --dbname restore_validation >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "${container}" pg_isready --username restore_admin --dbname restore_validation >/dev/null
  docker cp "${dump}" "${container}:/tmp/backup.dump" >/dev/null
  docker exec "${container}" pg_restore --exit-on-error --no-owner --no-privileges \
    --username restore_admin --dbname restore_validation /tmp/backup.dump
  relation_count="$(docker exec "${container}" psql --tuples-only --no-align --username restore_admin --dbname restore_validation \
    --command "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p');")"
  [[ "${relation_count}" -gt 0 ]] || { echo "Restored database is empty: ${label}" >&2; exit 1; }
  docker rm --force --volumes "${container}" >/dev/null
done < <(jq --raw-output '.artifacts[] | [.database,.file,.sha256] | @tsv' "${manifest}")

containers=()
echo "Encrypted offsite backup restored successfully into four isolated PostgreSQL containers: ${requested_key}"
