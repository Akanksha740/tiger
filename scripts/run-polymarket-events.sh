#!/usr/bin/env bash
# Ingest one Polymarket Gamma /events page with nested markets and outcomes.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "Missing .env - copy .env.example first"
  exit 1
fi

LIMIT="${LIMIT:-100}"
OFFSET="${OFFSET:-0}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Polymarket events ingest (limit=${LIMIT}, offset=${OFFSET}, heap=${MAVEN_OPTS})..."
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.polymarket-events.enabled=true \
  --tiger.ingestion.polymarket-events.limit=${LIMIT} \
  --tiger.ingestion.polymarket-events.offset=${OFFSET} \
  --tiger.ingestion.exit-on-complete=true"
