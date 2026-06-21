#!/usr/bin/env bash
# Backfill Polymarket Gamma /events with nested markets and outcomes.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "Missing .env - copy .env.example first"
  exit 1
fi

PAGE_LIMIT="${PAGE_LIMIT:-100}"
START_OFFSET="${START_OFFSET:-0}"
MAX_PAGES="${MAX_PAGES:-0}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Polymarket catalog ingest (page-limit=${PAGE_LIMIT}, start-offset=${START_OFFSET}, max-pages=${MAX_PAGES}, heap=${MAVEN_OPTS})..."
echo "MAX_PAGES=0 continues until Gamma returns fewer than page-limit events."

./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.polymarket-catalog.enabled=true \
  --tiger.ingestion.polymarket-catalog.page-limit=${PAGE_LIMIT} \
  --tiger.ingestion.polymarket-catalog.start-offset=${START_OFFSET} \
  --tiger.ingestion.polymarket-catalog.max-pages=${MAX_PAGES} \
  --tiger.ingestion.exit-on-complete=true"
