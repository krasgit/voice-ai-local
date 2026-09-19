#!/usr/bin/env bash
#
# setup.sh — one-time setup for the full local voice stack.
#
# Installs / builds everything needed for text chat + speech-to-text + text-to-speech:
#   - build tools (cmake) and espeak-ng (Piper runtime dependency / fallback TTS)
#   - whisper.cpp  (STT server)          -> $STACK_DIR/whisper.cpp
#   - llama.cpp    (LLM server)          -> $STACK_DIR/llama.cpp
#   - Piper        (neural TTS) + voices -> $STACK_DIR/piper, $STACK_DIR/piper-voices
#   - Qwen2.5-7B-Instruct chat model     -> $STACK_DIR/models  (unless DOWNLOAD_LLM=0)
#
# Re-running is safe: existing clones/binaries/models are reused.
#
# Usage:
#   ./scripts/setup.sh
#
# Override the install location (default /tmp/opencode):
#   STACK_DIR=$HOME/.voice-ai ./scripts/setup.sh
#
# Skip the ~4.7GB LLM download (bring your own model):
#   DOWNLOAD_LLM=0 ./scripts/setup.sh
#
set -euo pipefail

STACK_DIR="${STACK_DIR:-/tmp/opencode}"
# Multilingual "small" model handles Bulgarian + English (base.en is English-only).
WHISPER_MODEL_NAME="${WHISPER_MODEL_NAME:-small}"
DOWNLOAD_LLM="${DOWNLOAD_LLM:-1}"
LLM_FILE="${LLM_FILE:-Qwen2.5-3B-Instruct-Q4_K_M.gguf}"
LLM_URL_SRC="${LLM_URL_SRC:-https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf}"

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

mkdir -p "$STACK_DIR"

# ---------------------------------------------------------------------------
# 1. System dependencies
# ---------------------------------------------------------------------------
log "Checking system dependencies"
NEED_APT=()
command -v cmake     >/dev/null 2>&1 || NEED_APT+=(cmake)
command -v git       >/dev/null 2>&1 || NEED_APT+=(git)
command -v make      >/dev/null 2>&1 || NEED_APT+=(build-essential)
command -v espeak-ng >/dev/null 2>&1 || NEED_APT+=(espeak-ng)

if [ "${#NEED_APT[@]}" -gt 0 ]; then
  log "Installing: ${NEED_APT[*]}"
  sudo apt-get update -qq
  sudo apt-get install -y "${NEED_APT[@]}"
else
  echo "All system dependencies present."
fi

# ---------------------------------------------------------------------------
# 2. whisper.cpp (STT)
# ---------------------------------------------------------------------------
log "Setting up whisper.cpp"
if [ ! -d "$STACK_DIR/whisper.cpp/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/whisper.cpp "$STACK_DIR/whisper.cpp"
fi
if [ ! -x "$STACK_DIR/whisper.cpp/build/bin/whisper-server" ]; then
  cmake -S "$STACK_DIR/whisper.cpp" -B "$STACK_DIR/whisper.cpp/build" -DGGML_NATIVE=ON
  cmake --build "$STACK_DIR/whisper.cpp/build" --config Release -j"$(nproc)"
else
  echo "whisper-server already built."
fi
if [ ! -f "$STACK_DIR/whisper.cpp/models/ggml-${WHISPER_MODEL_NAME}.bin" ]; then
  ( cd "$STACK_DIR/whisper.cpp" && bash ./models/download-ggml-model.sh "$WHISPER_MODEL_NAME" )
else
  echo "Whisper model ggml-${WHISPER_MODEL_NAME}.bin already present."
fi

# ---------------------------------------------------------------------------
# 3. llama.cpp (LLM)
# ---------------------------------------------------------------------------
log "Setting up llama.cpp"
if [ ! -d "$STACK_DIR/llama.cpp/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$STACK_DIR/llama.cpp"
fi
if [ ! -x "$STACK_DIR/llama.cpp/build/bin/llama-server" ]; then
  # Explicit AVX2/FMA/F16C: GGML_NATIVE sometimes fails to detect these, leaving
  # a slow build. These flags are safe on any x86_64 CPU from ~2013 onward.
  cmake -S "$STACK_DIR/llama.cpp" -B "$STACK_DIR/llama.cpp/build" -DLLAMA_CURL=OFF \
      -DGGML_NATIVE=OFF -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON
  cmake --build "$STACK_DIR/llama.cpp/build" --config Release -j"$(nproc)" --target llama-server
else
  echo "llama-server already built."
fi

# ---------------------------------------------------------------------------
# 4. Piper (neural TTS) + voices
# ---------------------------------------------------------------------------
log "Setting up Piper"
PIPER_RELEASE="2023.11.14-2"
if [ ! -x "$STACK_DIR/piper/piper" ]; then
  curl -fL -o "$STACK_DIR/piper_linux_x86_64.tar.gz" \
    "https://github.com/rhasspy/piper/releases/download/${PIPER_RELEASE}/piper_linux_x86_64.tar.gz"
  tar xzf "$STACK_DIR/piper_linux_x86_64.tar.gz" -C "$STACK_DIR"
else
  echo "Piper already installed."
fi

mkdir -p "$STACK_DIR/piper-voices"
VOICES_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
download_voice() {
  # $1 = subpath under voices repo, $2 = local file stem
  local subpath="$1" stem="$2"
  if [ ! -f "$STACK_DIR/piper-voices/${stem}.onnx" ]; then
    curl -fL -o "$STACK_DIR/piper-voices/${stem}.onnx"      "$VOICES_BASE/${subpath}/${stem}.onnx"
    curl -fL -o "$STACK_DIR/piper-voices/${stem}.onnx.json" "$VOICES_BASE/${subpath}/${stem}.onnx.json"
  else
    echo "Voice ${stem} already present."
  fi
}
download_voice "bg/bg_BG/dimitar/medium" "bg_BG-dimitar-medium"
download_voice "en/en_US/lessac/medium"  "en_US-lessac-medium"
download_voice "en/en_US/ryan/high"      "en_US-ryan-high"

# ---------------------------------------------------------------------------
# 5. LLM chat model (Qwen2.5-7B-Instruct, ~4.7GB) — optional
# ---------------------------------------------------------------------------
log "Setting up LLM chat model"
mkdir -p "$STACK_DIR/models"
if [ "$DOWNLOAD_LLM" = "1" ]; then
  if [ ! -f "$STACK_DIR/models/$LLM_FILE" ]; then
    echo "Downloading $LLM_FILE (~4.7GB)…"
    curl -fL -o "$STACK_DIR/models/$LLM_FILE" "$LLM_URL_SRC"
  else
    echo "LLM model $LLM_FILE already present."
  fi
else
  echo "Skipping LLM download (DOWNLOAD_LLM=0). Bring your own GGUF and set LLM_MODEL."
fi

log "Setup complete."
cat <<EOF

Installed under: $STACK_DIR
  llama-server   : $STACK_DIR/llama.cpp/build/bin/llama-server
  whisper-server : $STACK_DIR/whisper.cpp/build/bin/whisper-server
  whisper model  : $STACK_DIR/whisper.cpp/models/ggml-${WHISPER_MODEL_NAME}.bin
  piper          : $STACK_DIR/piper/piper
  voices         : bg_BG-dimitar-medium, en_US-ryan-high (+ en_US-lessac-medium)
  LLM model      : $STACK_DIR/models/$LLM_FILE $( [ "$DOWNLOAD_LLM" = "1" ] || echo "(skipped)" )

Next:
  1. Start the backends (uses the downloaded model by default):
       LLM_MODEL=$STACK_DIR/models/$LLM_FILE ./scripts/start-services.sh
  2. Start the Java app:
       ./scripts/run.sh
  3. Open http://localhost:8080
EOF
