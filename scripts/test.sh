#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
if [[ -z "${JAVA_HOME}" ]]; then
  echo "Java 21 is required. Install JDK 21 or set JAVA_HOME."
  exit 1
fi

./mvnw test "$@"
