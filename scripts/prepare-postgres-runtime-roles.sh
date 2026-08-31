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

touch "${secret_file}"
chown root:root "${secret_file}"
chmod 0600 "${secret_file}"

ensure_secret() {
  local key="$1"
  if ! grep -q "^${key}=" "${secret_file}"; then
    printf '%s=%s\n' "${key}" "$(openssl rand -hex 32)" >>"${secret_file}"
  fi
}

ensure_secret MATCHMAKING_DB_RUNTIME_PASSWORD
ensure_secret MATCHMAKING_DB_MIGRATOR_PASSWORD
ensure_secret MATCH_DB_RUNTIME_PASSWORD
ensure_secret MATCH_DB_MIGRATOR_PASSWORD
ensure_secret PLAYER_DB_RUNTIME_PASSWORD
ensure_secret PLAYER_DB_MIGRATOR_PASSWORD
ensure_secret IDENTITY_DB_RUNTIME_PASSWORD
ensure_secret IDENTITY_DB_MIGRATOR_PASSWORD

# Passwords are generated locally on the VPS and are never printed.
# shellcheck disable=SC1090
source "${secret_file}"

prepare_runtime_role() {
  local service="$1"
  local bootstrap_role="$2"
  local runtime_role="$3"
  local migrator_role="$4"
  local database="$5"
  local runtime_password="$6"
  local migrator_password="$7"

  printf 'Preparing %s and %s in %s...\n' "${runtime_role}" "${migrator_role}" "${database}"

  "${compose[@]}" exec -T "${service}" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --username "${bootstrap_role}" --dbname "${database}" \
    --set=runtime_password="${runtime_password}" --set=migrator_password="${migrator_password}" <<SQL
BEGIN;
SELECT format(
  'CREATE ROLE ${runtime_role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 50 PASSWORD %L',
  :'runtime_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtime_role}')
\gexec
SELECT format(
  'CREATE ROLE ${migrator_role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 10 PASSWORD %L',
  :'migrator_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${migrator_role}')
\gexec
ALTER ROLE ${runtime_role} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 50 PASSWORD :'runtime_password';
ALTER ROLE ${migrator_role} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 10 PASSWORD :'migrator_password';
SELECT format(
  CASE c.relkind
    WHEN 'S' THEN 'ALTER SEQUENCE %I.%I OWNER TO ${migrator_role}'
    ELSE 'ALTER TABLE %I.%I OWNER TO ${migrator_role}'
  END,
  n.nspname,
  c.relname
)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p', 'S')
  AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = '${bootstrap_role}')
\gexec
ALTER DATABASE ${database} OWNER TO ${migrator_role};
GRANT CONNECT ON DATABASE ${database} TO ${migrator_role};
GRANT USAGE, CREATE ON SCHEMA public TO ${migrator_role};
GRANT CONNECT ON DATABASE ${database} TO ${runtime_role};
GRANT USAGE ON SCHEMA public TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${runtime_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${runtime_role};
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ${runtime_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator_role} IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${runtime_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator_role} IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${runtime_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator_role} IN SCHEMA public
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

  local migrator_attributes
  migrator_attributes="$("${compose[@]}" exec -T "${service}" \
    psql --no-psqlrc --tuples-only --no-align --username "${bootstrap_role}" --dbname "${database}" \
    --command "SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = '${migrator_role}';")"
  if [[ "${migrator_attributes}" != "f|f|f|f|f" ]]; then
    echo "Role validation failed for ${migrator_role}: ${migrator_attributes}" >&2
    exit 1
  fi

  "${compose[@]}" exec -T --env "PGPASSWORD=${runtime_password}" "${service}" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --host 127.0.0.1 \
    --username "${runtime_role}" --dbname "${database}" \
    --command 'SELECT count(*) FROM flyway_schema_history;' >/dev/null

  if "${compose[@]}" exec -T --env "PGPASSWORD=${runtime_password}" "${service}" \
    psql --no-psqlrc --host 127.0.0.1 --username "${runtime_role}" --dbname "${database}" \
    --command 'BEGIN; CREATE TABLE public.__runtime_must_not_create (id integer); ROLLBACK;' >/dev/null 2>&1; then
    echo "Runtime role ${runtime_role} unexpectedly has DDL privileges." >&2
    exit 1
  fi

  "${compose[@]}" exec -T --env "PGPASSWORD=${migrator_password}" "${service}" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --host 127.0.0.1 \
    --username "${migrator_role}" --dbname "${database}" <<SQL
BEGIN;
CREATE TABLE public.__migrator_role_probe (id integer PRIMARY KEY);
DROP TABLE public.__migrator_role_probe;
ROLLBACK;
SQL
}

prepare_runtime_role postgres matchmaking_service matchmaking_runtime matchmaking_migrator pinkward_matchmaking "${MATCHMAKING_DB_RUNTIME_PASSWORD}" "${MATCHMAKING_DB_MIGRATOR_PASSWORD}"
prepare_runtime_role match-postgres match_service match_runtime match_migrator pinkward_matches "${MATCH_DB_RUNTIME_PASSWORD}" "${MATCH_DB_MIGRATOR_PASSWORD}"
prepare_runtime_role player-postgres player_service player_runtime player_migrator pinkward_players "${PLAYER_DB_RUNTIME_PASSWORD}" "${PLAYER_DB_MIGRATOR_PASSWORD}"
prepare_runtime_role identity-postgres identity_service identity_runtime identity_migrator pinkward_identity "${IDENTITY_DB_RUNTIME_PASSWORD}" "${IDENTITY_DB_MIGRATOR_PASSWORD}"

update_production_environment() {
  local production_env="${infra_dir}/production.env"
  local temporary
  temporary="$(mktemp "${infra_dir}/production.env.XXXXXX")"
  chmod 0600 "${temporary}"
  awk -F= '
    !/^(MATCHMAKING_DB_RUNTIME_PASSWORD|MATCHMAKING_DB_MIGRATOR_PASSWORD|MATCH_DB_RUNTIME_PASSWORD|MATCH_DB_MIGRATOR_PASSWORD|PLAYER_DB_RUNTIME_PASSWORD|PLAYER_DB_MIGRATOR_PASSWORD|IDENTITY_DB_RUNTIME_PASSWORD|IDENTITY_DB_MIGRATOR_PASSWORD)=/
  ' "${production_env}" >"${temporary}"
  grep -E '^(MATCHMAKING_DB_RUNTIME_PASSWORD|MATCHMAKING_DB_MIGRATOR_PASSWORD|MATCH_DB_RUNTIME_PASSWORD|MATCH_DB_MIGRATOR_PASSWORD|PLAYER_DB_RUNTIME_PASSWORD|PLAYER_DB_MIGRATOR_PASSWORD|IDENTITY_DB_RUNTIME_PASSWORD|IDENTITY_DB_MIGRATOR_PASSWORD)=' "${secret_file}" >>"${temporary}"
  install -m 0600 -o root -g root "${temporary}" "${production_env}"
  rm -f "${temporary}"
}

update_production_environment
echo "PostgreSQL runtime and Flyway migrator roles are ready. Root-only credentials: ${secret_file}."
