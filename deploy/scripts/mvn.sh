#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME="${ETF_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
exec "$ROOT/.tools/apache-maven-3.9.11/bin/mvn" -s "$ROOT/deploy/scripts/maven-settings.xml" -f "$ROOT/backend/pom.xml" "$@"
