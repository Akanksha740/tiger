#!/usr/bin/env bash
# Ingest Kalshi events (nested markets + YES/NO outcomes) and open /markets.
# Skips /series by default (you already loaded series). Set REFRESH_SERIES=1 to reload.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]] || [[ ! -f secrets/kalshi_private.key ]]; then
  echo "Missing .env or secrets/kalshi_private.key — see README Configuration"
  exit 1
fi

REFRESH_SERIES="${REFRESH_SERIES:-false}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Kalshi catalog ingest (refresh-series=${REFRESH_SERIES}, heap=${MAVEN_OPTS})..."
echo "Processes one API page at a time (limit=50 events/page with nested markets). May take a while."

./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-catalog.enabled=true \
  --tiger.ingestion.kalshi-catalog.refresh-series=${REFRESH_SERIES} \
  --tiger.ingestion.exit-on-complete=true"
