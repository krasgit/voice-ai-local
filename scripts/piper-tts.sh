#!/usr/bin/env bash
#
# piper-tts.sh — TTS adapter the Java server invokes in place of espeak-ng.
#
# The server runs:  <bin> -v <voice> --stdin --stdout
# and writes text to stdin, expecting a WAV stream on stdout.
# We ignore those flags and use Piper with an auto-selected voice:
#   text contains Cyrillic -> Bulgarian voice, otherwise -> English voice.
#
# Wire it up by setting ESPEAK_BIN to this script (see scripts/start-app.sh).
#
set -euo pipefail

STACK_DIR="${STACK_DIR:-/tmp/opencode}"
PIPER_DIR="${PIPER_DIR:-$STACK_DIR/piper}"
VOICE_DIR="${PIPER_VOICE_DIR:-$STACK_DIR/piper-voices}"
BG_MODEL="${PIPER_BG_MODEL:-$VOICE_DIR/bg_BG-dimitar-medium.onnx}"
# EN voice can be overridden per request via PIPER_EN_VOICE (a stem name under
# VOICE_DIR), e.g. en_US-lessac-medium. Falls back to ryan-high.
EN_STEM="${PIPER_EN_VOICE:-en_US-ryan-high}"
EN_MODEL="${PIPER_EN_MODEL:-$VOICE_DIR/${EN_STEM}.onnx}"
[ -f "$EN_MODEL" ] || EN_MODEL="$VOICE_DIR/en_US-ryan-high.onnx"

# length_scale > 1.0 = slower speech (used by the "repeat slower" feature).
LENGTH_SCALE="${PIPER_LENGTH_SCALE:-1.0}"

export LD_LIBRARY_PATH="$PIPER_DIR:${LD_LIBRARY_PATH:-}"

text="$(cat)"

if printf '%s' "$text" | grep -qP '[\x{0400}-\x{04FF}]'; then
  model="$BG_MODEL"
else
  model="$EN_MODEL"
fi

printf '%s' "$text" | "$PIPER_DIR/piper" --model "$model" \
  --length_scale "$LENGTH_SCALE" --output_file - 2>/dev/null
