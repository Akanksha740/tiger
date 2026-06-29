#!/usr/bin/env bash
# Query Kalshi rows in Postgres.
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_postgres

run_sql() {
  tiger_run_sql "$1"
}

echo "=== counts (kalshi) ==="
run_sql "SELECT
  (SELECT COUNT(*) FROM series WHERE exchange='kalshi') AS series,
  (SELECT COUNT(*) FROM events WHERE exchange='kalshi') AS events,
  (SELECT COUNT(*) FROM markets WHERE exchange='kalshi') AS markets,
  (SELECT COUNT(*) FROM market_outcomes mo JOIN markets m ON m.id = mo.market_id WHERE m.exchange='kalshi') AS outcomes;"

echo ""
echo "=== sample series ==="
run_sql "SELECT source_series_id, category, total_volume, left(title, 50) AS title
FROM series WHERE exchange='kalshi' ORDER BY total_volume DESC NULLS LAST LIMIT 5;"

echo ""
echo "=== sample events ==="
run_sql "SELECT source_event_id, category, market_count, left(title, 45) AS title
FROM events WHERE exchange='kalshi' ORDER BY total_volume DESC NULLS LAST LIMIT 3;"

echo ""
echo "=== sample markets + outcomes ==="
run_sql "SELECT m.source_market_id, m.status, mo.outcome_key, mo.last_price, mo.best_bid, mo.best_ask
FROM markets m
JOIN market_outcomes mo ON mo.market_id = m.id
WHERE m.exchange='kalshi'
ORDER BY m.volume DESC NULLS LAST
LIMIT 6;"

echo ""
echo "=== latest ingestion_runs (kalshi) ==="
run_sql "SELECT entity_type, status, fetched_count, inserted_count, failed_count, started_at
FROM ingestion_runs WHERE exchange='kalshi' ORDER BY started_at DESC LIMIT 5;"
