#!/usr/bin/env bash
#
# start-services.sh — start the three backend services in the background:
#   - llama.cpp  LLM server    on :8081
#   - whisper.cpp STT server   on :8083
# (Piper TTS is invoked per-request by the Java app, so it needs no server.)
#
# Requires ./scripts/setup.sh to have been run first.
#
# Usage:
#   LLM_MODEL=/path/to/chat-model.gguf ./scripts/start-services.sh
#
# Logs are written to $STACK_DIR/logs/.
#
set -euo pipefail

STACK_DIR="${STACK_DIR:-/tmp/opencode}"
LOG_DIR="$STACK_DIR/logs"
mkdir -p "$LOG_DIR"

LLAMA_SERVER="${LLAMA_SERVER:-$STACK_DIR/llama.cpp/build/bin/llama-server}"
WHISPER_SERVER="${WHISPER_SERVER:-$STACK_DIR/whisper.cpp/build/bin/whisper-server}"
WHISPER_MODEL="${WHISPER_MODEL:-$STACK_DIR/whisper.cpp/models/ggml-small.bin}"
# Default to the fast 3B model (good speed on CPU). Override with LLM_MODEL=/path;
# use Qwen2.5-7B for higher quality if you can accept slower replies.
LLM_MODEL="${LLM_MODEL:-$STACK_DIR/models/Qwen2.5-3B-Instruct-Q4_K_M.gguf}"
# llama.cpp is fastest with one thread per PHYSICAL core, not per hyperthread.
LLM_THREADS="${LLM_THREADS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null | awk '{print int($1/2)}')}"
[ -z "$LLM_THREADS" ] || [ "$LLM_THREADS" -lt 1 ] 2>/dev/null && LLM_THREADS="$(nproc)"
LLM_CTX="${LLM_CTX:-4096}"

if [ ! -f "$LLM_MODEL" ]; then
  echo "ERROR: LLM model not found: $LLM_MODEL" >&2
  echo "  Run ./scripts/setup.sh, or set LLM_MODEL=/path/to/model.gguf" >&2
  exit 1
fi

# Best-effort: put CPUs in performance mode so they don't stay throttled at low
# clocks (a big cause of slow token generation on laptops). Reversible; ignored
# if not permitted. Disable with SET_GOVERNOR=0.
if [ "${SET_GOVERNOR:-1}" = "1" ] && [ -w /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ] 2>/dev/null; then
  for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    echo performance > "$g" 2>/dev/null || true
  done
elif [ "${SET_GOVERNOR:-1}" = "1" ] && command -v sudo >/dev/null 2>&1; then
  for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    echo performance | sudo tee "$g" >/dev/null 2>&1 || true
  done
fi

start_bg() {
  # $1 = human name, $2 = health url, rest = command
  local name="$1" url="$2"; shift 2
  if curl -sS -m 2 "$url" >/dev/null 2>&1; then
    echo "$name already running."
    return
  fi
  echo "Starting $name ..."
  setsid "$@" >"$LOG_DIR/${name}.log" 2>&1 </dev/null &
  disown || true
}

start_bg llama-server "http://127.0.0.1:8081/v1/models" \
  "$LLAMA_SERVER" -m "$LLM_MODEL" --host 127.0.0.1 --port 8081 \
  -c "$LLM_CTX" --threads "$LLM_THREADS" --no-webui

start_bg whisper-server "http://127.0.0.1:8083/" \
  "$WHISPER_SERVER" -m "$WHISPER_MODEL" --host 127.0.0.1 --port 8083

echo "Waiting for services to become ready ..."
for url in "http://127.0.0.1:8081/v1/models" "http://127.0.0.1:8083/"; do
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url" || true)
    [ "$code" = "200" ] && { echo "  ready: $url"; break; }
    sleep 1
  done
done

echo "Backends up. Logs in $LOG_DIR/. Now run: ./scripts/run.sh"
