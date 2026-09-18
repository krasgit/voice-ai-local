# syntax=docker/dockerfile:1
#
# All-in-one image for Voice AI Local:
#   Java app (:8080) + llama.cpp (:8081) + whisper.cpp (:8083) + Piper TTS.
#
# The GGUF chat model is NOT baked in (it is large and user-specific).
# Mount it at runtime and point LLM_MODEL at it. See docker-compose.yml.
#
# Build:  docker build -t voice-ai-local .
# Run:    docker run --rm -p 8080:8080 \
#           -v /path/to/models:/models \
#           -e LLM_MODEL=/models/your-model.gguf \
#           voice-ai-local

# ---------------------------------------------------------------------------
# Stage 1: build llama.cpp + whisper.cpp
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS native-build
RUN apt-get update && apt-get install -y --no-install-recommends \
      git cmake build-essential ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

ARG WHISPER_MODEL_NAME=small

WORKDIR /build
RUN git clone --depth 1 https://github.com/ggml-org/whisper.cpp \
    && cmake -S whisper.cpp -B whisper.cpp/build -DGGML_NATIVE=OFF \
    && cmake --build whisper.cpp/build --config Release -j "$(nproc)" \
    && bash whisper.cpp/models/download-ggml-model.sh ${WHISPER_MODEL_NAME} \
    && cp whisper.cpp/models/ggml-${WHISPER_MODEL_NAME}.bin /build/whisper-model.bin

RUN git clone --depth 1 https://github.com/ggml-org/llama.cpp \
    && cmake -S llama.cpp -B llama.cpp/build -DLLAMA_CURL=OFF -DGGML_NATIVE=OFF \
    && cmake --build llama.cpp/build --config Release -j "$(nproc)" --target llama-server

# ---------------------------------------------------------------------------
# Stage 2: Piper binary + voices
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS piper-fetch
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tar \
    && rm -rf /var/lib/apt/lists/*
ARG PIPER_RELEASE=2023.11.14-2
WORKDIR /piper-stage
RUN curl -fL -o piper.tgz \
      "https://github.com/rhasspy/piper/releases/download/${PIPER_RELEASE}/piper_linux_x86_64.tar.gz" \
    && tar xzf piper.tgz && rm piper.tgz
RUN mkdir -p voices \
    && BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main" \
    && curl -fL -o voices/bg_BG-dimitar-medium.onnx      "$BASE/bg/bg_BG/dimitar/medium/bg_BG-dimitar-medium.onnx" \
    && curl -fL -o voices/bg_BG-dimitar-medium.onnx.json "$BASE/bg/bg_BG/dimitar/medium/bg_BG-dimitar-medium.onnx.json" \
    && curl -fL -o voices/en_US-lessac-medium.onnx       "$BASE/en/en_US/lessac/medium/en_US-lessac-medium.onnx" \
    && curl -fL -o voices/en_US-lessac-medium.onnx.json  "$BASE/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json" \
    && curl -fL -o voices/en_US-ryan-high.onnx           "$BASE/en/en_US/ryan/high/en_US-ryan-high.onnx" \
    && curl -fL -o voices/en_US-ryan-high.onnx.json      "$BASE/en/en_US/ryan/high/en_US-ryan-high.onnx.json"

# ---------------------------------------------------------------------------
# Stage 3: build the Java app
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-11 AS java-build
WORKDIR /app
COPY server/pom.xml ./pom.xml
RUN mvn -q -e -B dependency:go-offline
COPY server/src ./src
RUN mvn -q -B package

# ---------------------------------------------------------------------------
# Stage 4: runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:11-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates espeak-ng libgomp1 \
    && rm -rf /var/lib/apt/lists/*

ENV STACK_DIR=/opt/stack
WORKDIR /opt/app

# Native servers + their co-located shared libraries (both live in build/bin).
COPY --from=native-build /build/whisper.cpp/build/bin/ ${STACK_DIR}/whisper.cpp/build/bin/
COPY --from=native-build /build/llama.cpp/build/bin/   ${STACK_DIR}/llama.cpp/build/bin/
COPY --from=native-build /build/whisper-model.bin      ${STACK_DIR}/whisper.cpp/models/ggml-small.bin

# Piper + voices
COPY --from=piper-fetch /piper-stage/piper        ${STACK_DIR}/piper
COPY --from=piper-fetch /piper-stage/voices       ${STACK_DIR}/piper-voices

# Java app + web assets + scripts
# The app resolves static files at "../web" relative to its working dir,
# so the JAR lives in /opt/app/server and web/ is its sibling.
COPY --from=java-build /app/target/voice-ai-local-server-1.0.0.jar ./server/app.jar
COPY web ./web
COPY scripts/piper-tts.sh ./piper-tts.sh
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x ./piper-tts.sh /usr/local/bin/entrypoint.sh

ENV LLM_URL=http://127.0.0.1:8081/v1/chat/completions \
    STT_URL=http://127.0.0.1:8083/inference \
    CHAT_MODEL=qwen \
    REASONING_MODEL=qwen \
    MAX_TOKENS=256 \
    VOICE_PORT=8080 \
    ESPEAK_BIN=/opt/app/piper-tts.sh \
    PIPER_DIR=${STACK_DIR}/piper \
    PIPER_VOICE_DIR=${STACK_DIR}/piper-voices

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -sf http://127.0.0.1:8080/ >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
