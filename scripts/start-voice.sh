#!/usr/bin/env bash
set -euo pipefail
WHISPER_SERVER="${WHISPER_SERVER:-/tmp/opencode/whisper.cpp/build/bin/whisper-server}"
WHISPER_MODEL="${WHISPER_MODEL:-/tmp/opencode/models/ggml-base.en.bin}"

if ! command -v espeak-ng >/dev/null 2>&1; then
  apk add espeak-ng
fi

if curl -sS -m 2 http://127.0.0.1:8083/ >/dev/null 2>&1; then
  echo "whisper-server already running on :8083"
else
  "$WHISPER_SERVER" -m "$WHISPER_MODEL" --host 127.0.0.1 --port 8083
fi