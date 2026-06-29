#!/usr/bin/env bash
# Ingest one Polymarket Gamma /events page with nested markets and outcomes.
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_postgres
tiger_prepare_java

LIMIT="${LIMIT:-100}"
OFFSET="${OFFSET:-0}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Polymarket events ingest (limit=${LIMIT}, offset=${OFFSET}, heap=${MAVEN_OPTS})..."
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.polymarket-events.enabled=true \
  --tiger.ingestion.polymarket-events.limit=${LIMIT} \
  --tiger.ingestion.polymarket-events.offset=${OFFSET} \
  --tiger.ingestion.exit-on-complete=true"
