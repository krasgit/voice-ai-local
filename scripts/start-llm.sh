#!/usr/bin/env bash
set -euo pipefail
MODEL="${LLM_MODEL:-/tmp/opencode/models/Qwen3-0.6B-Q8_0.gguf}"
LLAMA_SERVER="${LLAMA_SERVER:-/tmp/opencode/llama.cpp/build/bin/llama-server}"

if ! curl -sS -m 2 http://127.0.0.1:8081/v1/models >/dev/null 2>&1; then
  "$LLAMA_SERVER" -m "$MODEL" --host 127.0.0.1 --port 8081 -c 2048 --threads 7 --no-webui
else
  echo "llama-server already running on :8081"
fi