#!/usr/bin/env bash
# Query Polymarket rows in Postgres without a local psql install.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVICE="${TIGER_POSTGRES_SERVICE:-postgres}"

if ! docker compose ps --status running --format '{{.Service}}' 2>/dev/null | grep -qx "$SERVICE"; then
  echo "Postgres is not running. Start it with: docker compose up -d postgres"
  exit 1
fi

run_sql() {
  docker compose exec -T "$SERVICE" psql -U postgres -d tiger -c "$1"
}

echo "=== counts (polymarket) ==="
run_sql "SELECT
  (SELECT COUNT(*) FROM series WHERE exchange='polymarket') AS series,
  (SELECT COUNT(*) FROM events WHERE exchange='polymarket') AS events,
  (SELECT COUNT(*) FROM markets WHERE exchange='polymarket') AS markets,
  (SELECT COUNT(*) FROM market_outcomes mo JOIN markets m ON m.id = mo.market_id WHERE m.exchange='polymarket') AS outcomes;"

echo ""
echo "=== sample events ==="
run_sql "SELECT source_event_id, category, market_count, active_market_count, left(title, 55) AS title
FROM events WHERE exchange='polymarket' ORDER BY total_volume DESC NULLS LAST LIMIT 5;"

echo ""
echo "=== sample markets + outcomes ==="
run_sql "SELECT m.source_market_id, m.status, mo.outcome_key, mo.token_id, mo.last_price
FROM markets m
JOIN market_outcomes mo ON mo.market_id = m.id
WHERE m.exchange='polymarket'
ORDER BY m.volume DESC NULLS LAST
LIMIT 8;"

echo ""
echo "=== latest ingestion_runs (polymarket) ==="
run_sql "SELECT entity_type, status, fetched_count, inserted_count, failed_count, cursor_before, cursor_after, started_at
FROM ingestion_runs WHERE exchange='polymarket' ORDER BY started_at DESC LIMIT 5;"
