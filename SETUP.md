# Setup: Full Local Voice Stack

This guide sets up the complete local voice assistant: text chat, speech-to-text
(STT), and text-to-speech (TTS) — all running locally.

Components installed by `scripts/setup.sh`:

| Component      | Role                     | Port | Source            |
|----------------|--------------------------|------|-------------------|
| Java app       | UI + WebSocket + routing | 8080 | this repo         |
| llama.cpp      | LLM (chat/reasoning)     | 8081 | built from source |
| whisper.cpp    | STT (speech → text)      | 8083 | built from source |
| Piper          | TTS (text → speech)      | —    | prebuilt binary   |

Piper has no server; the Java app pipes text to `scripts/piper-tts.sh` per reply.

## Requirements

- Linux x86_64 (the Piper binary in setup is for `linux_x86_64`)
- Java 11+ and Maven
- `sudo` access for `apt-get` (to install `cmake`, `build-essential`, `espeak-ng`)
- ~8 GB free disk (multilingual Whisper model + Qwen2.5-7B chat model)

## 1. One-time setup

```bash
./scripts/setup.sh
```

This installs build dependencies, builds whisper.cpp and llama.cpp, downloads the
multilingual Whisper `small` model (handles Bulgarian + English), installs Piper
with voices (`bg_BG-dimitar-medium`, `en_US-ryan-high`), and downloads the
`Qwen2.5-7B-Instruct` chat model (~4.7 GB).

Skip the LLM download and bring your own model:

```bash
DOWNLOAD_LLM=0 ./scripts/setup.sh
```

Everything lands under `/tmp/opencode` by default. To keep it across reboots, pick
a persistent location:

```bash
STACK_DIR=$HOME/.voice-ai ./scripts/setup.sh
```

Use the same `STACK_DIR` for every command below (or `export` it once).

Re-running `setup.sh` is safe — it reuses existing clones, binaries, and models.

## 2. Start the backend services

Defaults to the model `setup.sh` downloaded, so just:

```bash
./scripts/start-services.sh
```

(Or override the model: `LLM_MODEL=/path/to/chat-model.gguf ./scripts/start-services.sh`.)

This launches `llama-server` (:8081) and `whisper-server` (:8083) in the
background and waits until both answer. Logs go to `$STACK_DIR/logs/`.

## 3. Start the app

```bash
./scripts/run.sh
```

`run.sh` sources `scripts/env.example`, routes TTS to `scripts/piper-tts.sh`,
builds the JAR if needed, and starts the app on :8080.

Open http://localhost:8080.

## How TTS voice selection works

`scripts/piper-tts.sh` reads the reply text and picks a voice automatically:

- text containing Cyrillic → Bulgarian voice (`bg_BG-dimitar-medium`)
- otherwise → English voice (`en_US-lessac-medium`)

Override the models via env vars if you add other voices:

```bash
export PIPER_BG_MODEL=/path/to/bg.onnx
export PIPER_EN_MODEL=/path/to/en.onnx
```

## Configuration reference

`scripts/env.example` (sourced by `run.sh`):

| Variable          | Default                                      | Meaning                          |
|-------------------|----------------------------------------------|----------------------------------|
| `VOICE_PORT`      | `8080`                                       | Java app port                    |
| `LLM_URL`         | `http://127.0.0.1:8081/v1/chat/completions`  | llama.cpp endpoint               |
| `CHAT_MODEL`      | `qwen`                                        | model name sent to llama.cpp     |
| `REASONING_MODEL` | `qwen`                                        | model name for reasoning route   |
| `STT_URL`         | `http://127.0.0.1:8083/inference`            | whisper.cpp endpoint             |
| `MAX_TOKENS`      | `256`                                        | max tokens per reply             |
| `ESPEAK_BIN`      | `scripts/piper-tts.sh`                        | TTS adapter (set by `run.sh`)    |

Service install paths (override before running scripts):

| Variable         | Default                                             |
|------------------|-----------------------------------------------------|
| `STACK_DIR`      | `/tmp/opencode`                                     |
| `LLAMA_SERVER`   | `$STACK_DIR/llama.cpp/build/bin/llama-server`        |
| `WHISPER_SERVER` | `$STACK_DIR/whisper.cpp/build/bin/whisper-server`    |
| `WHISPER_MODEL`  | `$STACK_DIR/whisper.cpp/models/ggml-base.en.bin`     |
| `PIPER_DIR`      | `$STACK_DIR/piper`                                   |
| `PIPER_VOICE_DIR`| `$STACK_DIR/piper-voices`                            |

## Troubleshooting

- `ERROR: LLM: Connection refused` — `llama-server` not running. Start it with
  `start-services.sh` and a valid `LLM_MODEL`.
- `ERROR: STT: Connection refused` — `whisper-server` not running (port 8083).
- `ERROR: TTS: ... espeak-ng ... No such file` — `ESPEAK_BIN` not resolving.
  `run.sh` sets it to the Piper wrapper; if you start the JAR manually, export
  `ESPEAK_BIN=./scripts/piper-tts.sh` yourself.
- Robotic/low-quality audio — you're on the espeak-ng fallback, not Piper. Make
  sure `ESPEAK_BIN` points at `scripts/piper-tts.sh` and Piper is installed.
- Text chat only needs `llama-server`; the microphone additionally needs
  `whisper-server`.

## Text-only mode (no microphone)

You only need the LLM. Skip Whisper/Piper:

```bash
LLM_MODEL=/path/to/model.gguf ./scripts/start-services.sh   # whisper still starts; ignore if unused
./scripts/run.sh
```

Then use the text box in the UI.
