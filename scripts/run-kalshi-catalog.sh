#!/usr/bin/env bash
# Ingest Kalshi events (nested markets + YES/NO outcomes) and open /markets.
# Skips /series by default (you already loaded series). Set REFRESH_SERIES=1 to reload.
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_env
tiger_require_postgres
tiger_prepare_java

REFRESH_SERIES="${REFRESH_SERIES:-false}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Kalshi catalog ingest (refresh-series=${REFRESH_SERIES}, heap=${MAVEN_OPTS})..."
echo "Processes one API page at a time (limit=50 events/page with nested markets). May take a while."

./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-catalog.enabled=true \
  --tiger.ingestion.kalshi-catalog.refresh-series=${REFRESH_SERIES} \
  --tiger.ingestion.exit-on-complete=true"
