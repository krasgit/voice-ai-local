#!/usr/bin/env bash
#
# Container entrypoint: start llama-server + whisper-server in the background,
# then run the Java app in the foreground (PID 1 concern handled by trap).
#
set -euo pipefail

STACK_DIR="${STACK_DIR:-/opt/stack}"
LLAMA_SERVER="$STACK_DIR/llama.cpp/build/bin/llama-server"
WHISPER_SERVER="$STACK_DIR/whisper.cpp/build/bin/whisper-server"
WHISPER_MODEL="${WHISPER_MODEL:-$STACK_DIR/whisper.cpp/models/ggml-base.en.bin}"
LLM_MODEL="${LLM_MODEL:-}"

# whisper-server / llama-server need their co-located shared libraries.
export LD_LIBRARY_PATH="$STACK_DIR/whisper.cpp/build/bin:$STACK_DIR/llama.cpp/build/bin:${LD_LIBRARY_PATH:-}"

pids=()
cleanup() {
  echo "Shutting down ..."
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup TERM INT

# --- STT: whisper-server (:8083) ---
if [ -x "$WHISPER_SERVER" ] && [ -f "$WHISPER_MODEL" ]; then
  echo "Starting whisper-server (STT) ..."
  "$WHISPER_SERVER" -m "$WHISPER_MODEL" --host 127.0.0.1 --port 8083 &
  pids+=($!)
else
  echo "WARN: whisper-server or model missing; STT disabled." >&2
fi

# --- LLM: llama-server (:8081) ---
if [ -n "$LLM_MODEL" ] && [ -f "$LLM_MODEL" ]; then
  echo "Starting llama-server (LLM) with $LLM_MODEL ..."
  "$LLAMA_SERVER" -m "$LLM_MODEL" --host 127.0.0.1 --port 8081 \
    -c "${LLM_CTX:-2048}" --threads "${LLM_THREADS:-$(nproc)}" --no-webui &
  pids+=($!)
else
  echo "WARN: LLM_MODEL not set or file missing ('$LLM_MODEL')." >&2
  echo "      Text chat and voice replies will fail until you mount a GGUF model" >&2
  echo "      and set LLM_MODEL. See docker-compose.yml." >&2
fi

# --- App: Java (:8080) in foreground ---
echo "Starting Java app on :${VOICE_PORT:-8080} ..."
cd /opt/app/server
java -jar /opt/app/server/app.jar &
app_pid=$!
pids+=("$app_pid")

# Wait on the app; if it exits, tear everything down.
wait "$app_pid"
cleanup
