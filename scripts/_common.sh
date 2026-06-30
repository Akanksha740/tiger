# Shared setup for one-shot ingestion scripts. Source from repo-root scripts only.
set -euo pipefail

_tiger_repo_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
}

_tiger_load_env() {
  _tiger_repo_root
  if [[ ! -f .env ]]; then
    return 0
  fi
  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    val="${BASH_REMATCH[2]}"
    # Preserve variables already set on the command line or parent shell.
    if [[ -z "${!key+x}" ]]; then
      export "$key=$val"
    fi
  done < .env
}

_tiger_load_db_config() {
  _tiger_load_env
  local datasource_url="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/tiger}"
  TIGER_DB_USER="${SPRING_DATASOURCE_USERNAME:-postgres}"
  TIGER_DB_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-postgres}"
  local host_port_db="${datasource_url#jdbc:postgresql://}"
  local host_port="${host_port_db%%/*}"
  TIGER_DB_NAME="${host_port_db#*/}"
  TIGER_DB_NAME="${TIGER_DB_NAME%%\?*}"
  TIGER_DB_HOST="${host_port%%:*}"
  TIGER_DB_PORT="5432"
  if [[ "$host_port" == *:* ]]; then
    TIGER_DB_PORT="${host_port##*:}"
  fi
}

tiger_require_env() {
  _tiger_repo_root
  if [[ ! -f .env ]]; then
    echo "Missing .env — copy .env.example and set KALSHI_KEY_ID / secrets/kalshi_private.key"
    exit 1
  fi
  if [[ ! -f secrets/kalshi_private.key ]]; then
    echo "Missing secrets/kalshi_private.key"
    exit 1
  fi
}

tiger_require_postgres() {
  _tiger_load_db_config

  if command -v pg_isready >/dev/null 2>&1; then
    if PGPASSWORD="$TIGER_DB_PASSWORD" pg_isready \
      -h "$TIGER_DB_HOST" \
      -p "$TIGER_DB_PORT" \
      -U "$TIGER_DB_USER" \
      -d "$TIGER_DB_NAME" >/dev/null 2>&1; then
      echo "Using Postgres at ${TIGER_DB_HOST}:${TIGER_DB_PORT}/${TIGER_DB_NAME}"
      return 0
    fi
  fi

  local service="${TIGER_POSTGRES_SERVICE:-postgres}"
  if ! command -v docker >/dev/null 2>&1; then
    echo "Postgres is not reachable at ${TIGER_DB_HOST}:${TIGER_DB_PORT}/${TIGER_DB_NAME}, and docker is not installed."
    echo "Start local Postgres or install Docker, then retry."
    exit 1
  fi

  if ! docker compose ps --status running --format '{{.Service}}' 2>/dev/null | grep -qx "$service"; then
    echo "Postgres is not running. Starting docker compose postgres..."
    docker compose up -d postgres
    echo "Waiting for Postgres to accept connections..."
    for _ in {1..30}; do
      if docker compose exec -T "$service" pg_isready -U postgres -d tiger >/dev/null 2>&1; then
        return 0
      fi
      sleep 1
    done
    echo "Postgres did not become ready. Check: docker compose logs postgres"
    exit 1
  fi
}

tiger_run_sql() {
  _tiger_load_db_config
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$TIGER_DB_PASSWORD" psql \
      -h "$TIGER_DB_HOST" \
      -p "$TIGER_DB_PORT" \
      -U "$TIGER_DB_USER" \
      -d "$TIGER_DB_NAME" \
      -c "$1"
    return 0
  fi

  local service="${TIGER_POSTGRES_SERVICE:-postgres}"
  if command -v docker >/dev/null 2>&1; then
    docker compose exec -T "$service" psql -U postgres -d tiger -c "$1"
    return 0
  fi

  echo "psql is not installed and docker is not available."
  exit 1
}

tiger_prepare_java() {
  if [[ -z "${JAVA_HOME:-}" ]]; then
    if JAVA_21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
      export JAVA_HOME="$JAVA_21_HOME"
    elif JAVA_DEFAULT_HOME="$(/usr/libexec/java_home 2>/dev/null)"; then
      export JAVA_HOME="$JAVA_DEFAULT_HOME"
      echo "Java 21 was not found; using JAVA_HOME=${JAVA_HOME}"
    else
      echo "Java is required. Install JDK 21 or set JAVA_HOME."
      exit 1
    fi
  fi
  # Load .env defaults without overriding variables already exported by the caller.
  _tiger_load_env
}
