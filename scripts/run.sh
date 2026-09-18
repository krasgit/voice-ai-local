#!/usr/bin/env bash
#
# run.sh — build (if needed) and start the Java voice app on :8080.
#
# TTS is routed to Piper via scripts/piper-tts.sh (set through ESPEAK_BIN).
# Backend services (LLM :8081, STT :8083) are started separately:
#   LLM_MODEL=/path/to/model.gguf ./scripts/start-services.sh
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../server"

# Load env.example if present (does not override already-set vars).
if [ -f "$HERE/env.example" ]; then
  # shellcheck disable=SC1091
  set -a; . "$HERE/env.example"; set +a
fi

# Route TTS to Piper unless the caller overrides ESPEAK_BIN.
export ESPEAK_BIN="${ESPEAK_BIN:-$HERE/piper-tts.sh}"

mvn -q package
exec java -jar target/voice-ai-local-server-1.0.0.jar
