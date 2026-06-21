#!/usr/bin/env bash
# One-shot Kalshi /series ingest. Run from repo root; uses .env via Spring Boot import.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "Missing .env — copy .env.example and set KALSHI_KEY_ID / secrets/kalshi_private.key"
  exit 1
fi
if [[ ! -f secrets/kalshi_private.key ]]; then
  echo "Missing secrets/kalshi_private.key"
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-series.enabled=true \
  --tiger.ingestion.exit-on-complete=true"
