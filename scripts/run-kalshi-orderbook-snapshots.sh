#!/usr/bin/env bash
# Poll all active Kalshi markets and capture multiple orderbook snapshots per market.
# Requires prior catalog ingest (run-kalshi-catalog.sh or run-kalshi-catalog-incremental.sh).
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_env
tiger_require_postgres
tiger_prepare_java

# 0 = all active markets; 3 samples 1 second apart by default
LIMIT="${LIMIT:-0}"
SAMPLES="${SAMPLES:-3}"
SAMPLE_INTERVAL_MS="${SAMPLE_INTERVAL_MS:-1000}"
PARALLELISM="${PARALLELISM:-16}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

echo "Starting Kalshi orderbook poll (limit=${LIMIT}, samples=${SAMPLES}, sampleIntervalMs=${SAMPLE_INTERVAL_MS}, parallelism=${PARALLELISM}, heap=${MAVEN_OPTS})..."

./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-orderbook-snapshots.enabled=true \
  --tiger.ingestion.kalshi-orderbook-snapshots.limit=${LIMIT} \
  --tiger.ingestion.kalshi-orderbook-snapshots.samples=${SAMPLES} \
  --tiger.ingestion.kalshi-orderbook-snapshots.sample-interval-ms=${SAMPLE_INTERVAL_MS} \
  --tiger.ingestion.kalshi-orderbook-snapshots.parallelism=${PARALLELISM} \
  --tiger.ingestion.exit-on-complete=true"
