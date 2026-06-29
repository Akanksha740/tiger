#!/usr/bin/env bash
# Ingest Kalshi /events with nested markets and YES/NO outcomes only.
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_env
tiger_require_postgres
tiger_prepare_java
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-events.enabled=true \
  --tiger.ingestion.exit-on-complete=true"
