#!/usr/bin/env bash
# Ingest Kalshi /events with nested markets and YES/NO outcomes only.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]] || [[ ! -f secrets/kalshi_private.key ]]; then
  echo "Missing .env or secrets/kalshi_private.key"
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-events.enabled=true \
  --tiger.ingestion.exit-on-complete=true"
