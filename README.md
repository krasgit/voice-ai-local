# Voice AI Local

Local, ChatGPT-like voice assistant:
Browser/Android -> WebSocket -> Java Undertow -> Intent Router -> llama.cpp -> Piper/Whisper adapters.

This starter is intentionally dependency-light:
- Java 11
- Undertow WebSocket server
- Browser microphone capture
- Text chat works immediately when `llama-server` is running
- Audio/STT/TTS are adapter-ready and can be enabled through scripts
- BG + English mixed conversation
- General chat / English teacher / reasoning routing
- Interrupt endpoint

## 1. Build

```bash
cd server
mvn package
```

## 2. Start llama.cpp

Run a local llama.cpp server, for example:

```bash
llama-server -m /path/to/model.gguf --host 127.0.0.1 --port 8081
```

Set:
```bash
export LLM_URL=http://127.0.0.1:8081/v1/chat/completions
```

## 3. Start Java server

```bash
cd server
mvn exec:java
```

or:
```bash
java -jar target/voice-ai-local-server.jar
```

The server listens on port 8080.

## 4. Open UI

On the PC:
http://localhost:8080/

From Android on the same Wi-Fi:
http://PC_IP:8080/

For Android microphone access, use HTTPS in production or a browser context that permits microphone access. For LAN testing, Chrome may require a secure origin depending on device/browser policy.

## 5. Configuration

Environment variables:

- `VOICE_PORT=8080`
- `LLM_URL=http://127.0.0.1:8081/v1/chat/completions`
- `CHAT_MODEL=qwen3-1.7b`
- `REASONING_MODEL=qwen3-14b`
- `MAX_TOKENS=256`

The model name is sent to llama.cpp; use the name your server accepts.

## Architecture

```text
Android Browser
  microphone / speaker / UI
          |
       WebSocket
          |
Java 11 + Undertow
  Session
  IntentRouter
      |--------- GENERAL_CHAT --------> fast model
      |--------- ENGLISH_LEARNING ----> fast model
      `--------- REASONING/CODE -------> reasoning model
          |
       LLM HTTP
          |
      response text
          |
   browser speech/audio
```

## Current audio path

The browser captures microphone audio and sends a placeholder `audio` WebSocket message. The server intentionally does not pretend to decode arbitrary browser audio as WAV/PCM.

For production audio:
1. Use AudioWorklet to send PCM16 frames.
2. Feed frames to whisper.cpp streaming mode.
3. Send partial/final transcript back as `transcript`.
4. Route intent.
5. Stream LLM tokens.
6. Buffer complete sentences.
7. Send each sentence to Piper.
8. Stream generated PCM/WAV audio back.
9. Stop TTS and cancel generation on `interrupt`.

This separation keeps the starter genuinely runnable instead of hiding native audio assumptions.

## English Teacher mode

The UI has a mode selector. The backend injects a teacher instruction:

Speak mainly in English.
Correct important mistakes after the user finishes.
Use Bulgarian for difficult explanations.
Do not correct every tiny mistake.
Keep conversation natural.

## Security

This is a local development server. Do not expose it directly to the Internet. If you bind to `0.0.0.0`, use LAN/firewall restrictions and authentication before exposing it beyond your trusted network.

## Бърз старт (български)

### Нужни програми

- Java 11+ и Maven
- `llama-server` от llama.cpp и GGUF модел
- По избор за разговор с микрофон: `whisper-server`, Whisper модел и `espeak-ng`

### Инсталиране и стартиране

В първи терминал стартирайте езиковия модел (сменете пътя):

```sh
llama-server -m /path/to/model.gguf --host 127.0.0.1 --port 8081
```

Във втори терминал, в главната папка на проекта: 

```sh
./scripts/run.sh
```

При първия път Maven сваля нужните Java библиотеки и създава JAR файла. Отворете http://localhost:8080.

### Как се ползва

1. Изчакайте статус `connected`.
2. Изберете режим Chat, English teacher или Reasoning/Code.
3. Напишете текст и натиснете Send.
4. За глас натиснете Start microphone, разрешете микрофона и говорете. След пауза записът се праща автоматично.
5. Stop прекъсва отговора.

Текстовият чат изисква само `llama-server`. За микрофон стартирайте отделно Whisper услугата: 

```sh
STT_URL=http://127.0.0.1:8083/inference ./scripts/start-voice.sh
```

При други пътища задайте `WHISPER_SERVER=/path/to/whisper-server` и `WHISPER_MODEL=/path/to/ggml-model.bin` преди тази команда.

### Настройки

Примерните променливи са в `scripts/env.example`: `VOICE_PORT`, `LLM_URL`, `CHAT_MODEL`, `REASONING_MODEL` и `MAX_TOKENS`. За телефон в същата Wi-Fi мрежа отворете `http://IP-НА-КОМПЮТЪРА:8080`. За микрофон на телефон може да е нужен HTTPS. Не излагайте сървъра директно в интернет.
