#!/usr/bin/env bash
# Incremental Kalshi catalog sync:
#   1. GET /series?min_updated_ts=... (full series when REFRESH_SERIES=1)
#   2. GET /events?series_ticker=... for each newly inserted series
#   3. GET /events?min_updated_ts=... for event-level changes
#   4. GET /markets?status=open
# Run a full backfill first via run-kalshi-catalog.sh when the DB is empty.
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_env
tiger_require_postgres
tiger_prepare_java

REFRESH_SERIES="${REFRESH_SERIES:-false}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Kalshi incremental catalog sync (refresh-series=${REFRESH_SERIES}, heap=${MAVEN_OPTS})..."

./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-catalog.enabled=true \
  --tiger.ingestion.kalshi-catalog.incremental=true \
  --tiger.ingestion.kalshi-catalog.refresh-series=${REFRESH_SERIES} \
  --tiger.ingestion.exit-on-complete=true"
