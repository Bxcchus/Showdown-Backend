#!/usr/bin/env bash
set -Eeuo pipefail

infra_dir="${SHOWDOWN_INFRA_DIR:-/opt/pinkward/showdown-backend/infra}"
secret_dir="${SHOWDOWN_HOST_SECRET_DIR:-/etc/gyms-lol}"
secret_file="${secret_dir}/postgres-runtime.env"
compose=(docker compose --env-file production.env -f compose.yml -f compose.production.yml)

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root." >&2
  exit 1
fi

cd "${infra_dir}"
install -d -m 0700 -o root -g root "${secret_dir}"

if [[ ! -f "${secret_file}" ]]; then
  old_umask="$(umask)"
  umask 077
  {
    printf 'MATCHMAKING_DB_RUNTIME_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'MATCH_DB_RUNTIME_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'PLAYER_DB_RUNTIME_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'IDENTITY_DB_RUNTIME_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  } >"${secret_file}"
  umask "${old_umask}"
fi
chown root:root "${secret_file}"
chmod 0600 "${secret_file}"

# Passwords are generated locally on the VPS and are never printed.
# shellcheck disable=SC1090
source "${secret_file}"

prepare_runtime_role() {
  local service="$1"
  local bootstrap_role="$2"
  local runtime_role="$3"
  local database="$4"
  local runtime_password="$5"

  printf 'Preparing limited role %s in %s...\n' "${runtime_role}" "${database}"

  "${compose[@]}" exec -T "${service}" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --username "${bootstrap_role}" --dbname "${database}" \
    --set=runtime_password="${runtime_password}" <<SQL
BEGIN;
SELECT format(
  'CREATE ROLE ${runtime_role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 50 PASSWORD %L',
  :'runtime_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtime_role}')
\gexec
ALTER ROLE ${runtime_role} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 50 PASSWORD :'runtime_password';
GRANT CONNECT ON DATABASE ${database} TO ${runtime_role};
GRANT USAGE ON SCHEMA public TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${runtime_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${runtime_role};
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ${runtime_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${bootstrap_role} IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${runtime_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${bootstrap_role} IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${runtime_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${bootstrap_role} IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO ${runtime_role};
COMMIT;
SQL

  local role_attributes
  role_attributes="$("${compose[@]}" exec -T "${service}" \
    psql --no-psqlrc --tuples-only --no-align --username "${bootstrap_role}" --dbname "${database}" \
    --command "SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = '${runtime_role}';")"
  if [[ "${role_attributes}" != "f|f|f|f|f" ]]; then
    echo "Role validation failed for ${runtime_role}: ${role_attributes}" >&2
    exit 1
  fi

  "${compose[@]}" exec -T --env "PGPASSWORD=${runtime_password}" "${service}" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --host 127.0.0.1 \
    --username "${runtime_role}" --dbname "${database}" \
    --command 'SELECT count(*) FROM flyway_schema_history;' >/dev/null
}

prepare_runtime_role postgres matchmaking_service matchmaking_runtime pinkward_matchmaking "${MATCHMAKING_DB_RUNTIME_PASSWORD}"
prepare_runtime_role match-postgres match_service match_runtime pinkward_matches "${MATCH_DB_RUNTIME_PASSWORD}"
prepare_runtime_role player-postgres player_service player_runtime pinkward_players "${PLAYER_DB_RUNTIME_PASSWORD}"
prepare_runtime_role identity-postgres identity_service identity_runtime pinkward_identity "${IDENTITY_DB_RUNTIME_PASSWORD}"

echo "Limited PostgreSQL runtime roles are ready but not activated. Root-only credentials: ${secret_file}."
