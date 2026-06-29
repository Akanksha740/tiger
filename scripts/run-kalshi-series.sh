#!/usr/bin/env bash
# One-shot Kalshi /series ingest. Run from repo root; uses .env via Spring Boot import.
set -euo pipefail
# shellcheck source=_common.sh
source "$(dirname "$0")/_common.sh"
tiger_require_env
tiger_require_postgres
tiger_prepare_java
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --tiger.ingestion.kalshi-series.enabled=true \
  --tiger.ingestion.exit-on-complete=true"
