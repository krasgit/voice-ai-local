#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../server"
mvn package
exec java -jar target/voice-ai-local-server-1.0.0.jar
