package local.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.undertow.Undertow;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceServer {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("VOICE_PORT", "8080"));
    static final String LLM_URL = System.getenv().getOrDefault(
            "LLM_URL", "http://127.0.0.1:8081/v1/chat/completions");
    static final String STT_URL = System.getenv().getOrDefault(
            "STT_URL", "http://127.0.0.1:8083/inference");
    // Whisper language: "bg", "en", or "auto". Default "bg" transcribes Bulgarian
    // reliably while still handling embedded English words; "auto" is less reliable
    // for mixed speech. Requires a multilingual model (e.g. ggml-small.bin).
    static final String STT_LANG = System.getenv().getOrDefault("STT_LANG", "bg");
    static final String ESPEAK_BIN = System.getenv().getOrDefault("ESPEAK_BIN", "espeak-ng");
    static final String ESPEAK_VOICE = System.getenv().getOrDefault("ESPEAK_VOICE", "en-us");
    static final String CHAT_MODEL = System.getenv().getOrDefault("CHAT_MODEL", "qwen3-1.7b");
    static final String REASONING_MODEL = System.getenv().getOrDefault("REASONING_MODEL", "qwen3-14b");
    static final int MAX_TOKENS = Integer.parseInt(System.getenv().getOrDefault("MAX_TOKENS", "256"));
    // Keep at most this many past messages (user+assistant) in the context window.
    static final int MAX_HISTORY = Integer.parseInt(System.getenv().getOrDefault("MAX_HISTORY", "12"));

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) {
        HttpHandler wsHandler = Handlers.websocket(new WSCallback());

        HttpHandler root = exchange -> {
            String path = exchange.getRequestPath();
            if ("/ws".equals(path)) {
                wsHandler.handleRequest(exchange);
                return;
            }
            serveStatic(exchange, path);
        };

        Undertow.Builder b = Undertow.builder()
                .addHttpListener(PORT, "0.0.0.0")
                .setHandler(root);

        // Optional HTTPS listener so phones can access the microphone (which
        // browsers require a secure origin for). Enable by setting:
        //   TLS_KEYSTORE=/path/to/keystore.p12  TLS_PASSWORD=...  [TLS_PORT=8443]
        String ksPath = System.getenv("TLS_KEYSTORE");
        String ksPass = System.getenv("TLS_PASSWORD");
        int tlsPort = Integer.parseInt(System.getenv().getOrDefault("TLS_PORT", "8443"));
        if (ksPath != null && ksPass != null) {
            try {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(Paths.get(ksPath))) {
                    ks.load(in, ksPass.toCharArray());
                }
                javax.net.ssl.KeyManagerFactory kmf =
                        javax.net.ssl.KeyManagerFactory.getInstance(
                                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, ksPass.toCharArray());
                javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
                ssl.init(kmf.getKeyManagers(), null, null);
                b.addHttpsListener(tlsPort, "0.0.0.0", ssl);
                System.out.println("Voice AI Local (TLS): https://0.0.0.0:" + tlsPort);
            } catch (Exception e) {
                System.err.println("TLS setup failed, continuing HTTP-only: " + e.getMessage());
            }
        }

        b.build().start();

        System.out.println("Voice AI Local: http://0.0.0.0:" + PORT);
        System.out.println("LLM: " + LLM_URL);
    }

    static void serveStatic(HttpServerExchange ex, String path) {
        if (path.equals("/")) path = "/index.html";
        Path p = Paths.get("../web").resolve(path.substring(1)).normalize();
        if (!p.startsWith(Paths.get("../web").normalize()) || !Files.isRegularFile(p)) {
            ex.setStatusCode(404);
            ex.getResponseSender().send("Not found");
            return;
        }
        try {
            byte[] data = Files.readAllBytes(p);
            String ct = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : path.endsWith(".json") ? "application/manifest+json; charset=utf-8"
                    : path.endsWith(".svg") ? "image/svg+xml"
                    : "text/css; charset=utf-8";
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, ct);
            ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            ex.getResponseSender().send(new String(data, StandardCharsets.UTF_8));
        } catch (IOException e) {
            ex.setStatusCode(500);
            ex.getResponseSender().send(e.toString());
        }
    }

    static class WSCallback implements WebSocketConnectionCallback {
        public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
            Session session = new Session(channel);
            channel.getReceiveSetter().set(new AbstractReceiveListener() {
                @Override
                protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                    try {
                        session.handle(JSON.readTree(msg.getData()));
                    } catch (Exception e) {
                        session.send(error(e.getMessage()));
                    }
                }

                @Override
                protected void onFullBinaryMessage(WebSocketChannel ch, BufferedBinaryMessage msg) {
                    try (org.xnio.Pooled<ByteBuffer[]> pooled = msg.getData()) {
                        ByteBuffer[] bufs = pooled.getResource();
                        int total = 0;
                        for (ByteBuffer b : bufs) total += b.remaining();
                        byte[] bytes = new byte[total];
                        int off = 0;
                        for (ByteBuffer b : bufs) {
                            int r = b.remaining();
                            b.get(bytes, off, r);
                            off += r;
                        }
                        session.handleAudio(bytes);
                    }
                }
            });
            channel.resumeReceives();
            session.send(event("ready", "Voice AI ready"));
        }
    }

    static class Session {
        final WebSocketChannel channel;
        final List<Map<String,String>> history = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ByteArrayOutputStream audioBuf = new ByteArrayOutputStream();
        volatile boolean inAudio = false;
        String mode = "chat";
        String difficulty = "";   // "", A1, A2, B1, B2 — adjusts language complexity
        String speechLang = "";   // "", "bg", "en" — manual STT language override
        String practiceTarget = "";  // current pronunciation target phrase
        String drillAnswer = "";     // hidden expected answer for the current drill
        String dictationTarget = ""; // hidden dictation sentence (heard, not shown)
        String roleplayPrompt = "";  // active role-play scenario system prompt
        String listenPassage = "";   // hidden listening-comprehension passage
        String listenQuestions = ""; // the questions asked about the passage
        // Current Piper TTS process, so interrupt can kill in-flight speech.
        volatile Process ttsProc = null;
        // Monotonic id for ordering streamed audio chunks on the client.
        int audioSeq = 0;

        Session(WebSocketChannel c) { channel = c; }

        // Drop old turns so the context window stays bounded.
        void trimHistory() {
            while (history.size() > MAX_HISTORY) history.remove(0);
        }

        // Teacher / practice / drill / dictation / roleplay expect English speech.
        // A manual override (speechLang: "bg"/"en") wins when set; "auto"/"" uses
        // the mode-based default.
        String sttLang() {
            if (speechLang.equals("bg") || speechLang.equals("en")) return speechLang;
            switch (mode) {
                case "teacher": case "practice": case "drill":
                case "dictation": case "roleplay": return "en";
                default: return STT_LANG;
            }
        }

        // Adds a CEFR-level instruction so replies match the learner's level.
        String difficultyClause() {
            switch (difficulty) {
                case "A1": return "\nUse very simple English (CEFR A1): short sentences, basic words.";
                case "A2": return "\nUse simple English (CEFR A2): common words, short clear sentences.";
                case "B1": return "\nUse intermediate English (CEFR B1): everyday vocabulary, moderate length.";
                case "B2": return "\nUse upper-intermediate English (CEFR B2): richer vocabulary is fine.";
                default:   return "";
            }
        }

        void handle(JsonNode n) {
            String type = n.path("type").asText("");
            switch (type) {
                case "config":
                    mode = n.path("mode").asText("chat");
                    difficulty = n.path("difficulty").asText("");
                    speechLang = n.path("speechLang").asText("");
                    send(event("status", "mode=" + mode));
                    break;
                case "text":
                case "transcript":
                    String text = n.path("text").asText("").trim();
                    if (text.isEmpty()) break;
                    if (mode.equals("practice") && !practiceTarget.isEmpty()) scorePronunciation(text);
                    else if (mode.equals("drill") && !drillAnswer.isEmpty()) drillCheck(text);
                    else if (mode.equals("dictation") && !dictationTarget.isEmpty()) dictationCheck(text);
                    else ask(text);
                    break;
                case "translate":
                    translate(n.path("text").asText("").trim());
                    break;
                case "practice_next":
                    practiceNext();
                    break;
                case "practice_repeat":
                    if (!practiceTarget.isEmpty()) reSpeak(practiceTarget, n.path("speed").asDouble(1.0), -1);
                    break;
                case "drill_next":
                    drillNext();
                    break;
                case "drill_answer":
                    drillCheck(n.path("text").asText("").trim());
                    break;
                case "dict_next":
                    dictationNext();
                    break;
                case "dict_repeat":
                    if (!dictationTarget.isEmpty()) reSpeak(dictationTarget, n.path("speed").asDouble(1.0), -1);
                    break;
                case "dict_check":
                    dictationCheck(n.path("text").asText("").trim());
                    break;
                case "roleplay_start":
                    roleplayStart(n.path("scenario").asText("cafe"));
                    break;
                case "speak":
                    // Re-speak arbitrary text at a given speed (repeat-slower / TTS
                    // for words). speed>1 = slower. Optional "id" echoed back.
                    reSpeak(n.path("text").asText("").trim(),
                            n.path("speed").asDouble(1.0),
                            n.path("id").asInt(-1));
                    break;
                case "interrupt":
                    cancelled.set(true);
                    Process pr = ttsProc;
                    if (pr != null) pr.destroyForcibly();
                    send(event("interrupted", "generation cancelled"));
                    break;
                case "audio_start":
                    inAudio = true;
                    audioBuf.reset();
                    break;
                case "audio_end":
                    inAudio = false;
                    handleVoice(audioBuf.toByteArray());
                    audioBuf.reset();
                    break;
                case "audio":
                    send(event("status", "voice page is out of date - refresh the browser (Ctrl+R)"));
                    break;
                default:
                    send(error("Unknown message type: " + type));
            }
        }

        void handleAudio(byte[] bytes) {
            if (!inAudio || bytes.length == 0) return;
            audioBuf.write(bytes, 0, bytes.length);
        }

        void handleVoice(byte[] pcm) {
            if (pcm.length < 800) { // under ~25ms of 16 kHz audio
                send(event("status", "no audio received"));
                return;
            }
            send(event("status", "transcribing..."));
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] wav = pcmToWav(pcm);
                    String text = transcribe(wav);
                    if (text.isEmpty()) {
                        send(event("status", "could not hear anything"));
                        return;
                    }
                    send(event("transcript", text));
                    if (mode.equals("practice") && !practiceTarget.isEmpty()) {
                        scorePronunciation(text);
                    } else if (mode.equals("drill") && !drillAnswer.isEmpty()) {
                        drillCheck(text);
                    } else {
                        ask(text);
                    }
                } catch (Exception e) {
                    send(error("STT: " + e.getMessage()));
                }
            });
        }

        void ask(String user) {
            cancelled.set(false);
            String intent, instruction;
            boolean reasoning = false;
            if (mode.equals("roleplay") && !roleplayPrompt.isEmpty()) {
                intent = "ROLE_PLAY";
                instruction = roleplayPrompt + difficultyClause();
            } else {
                IntentRouter.Result r = IntentRouter.route(user, mode);
                intent = r.intent;
                reasoning = r.reasoning;
                instruction = r.systemPrompt + difficultyClause();
            }
            final String fIntent = intent, fInstruction = instruction;
            final boolean fReasoning = reasoning;
            history.add(Map.of("role", "user", "content", user));
            trimHistory();

            CompletableFuture.runAsync(() -> {
                try {
                    send(event("intent", fIntent));
                    String model = fReasoning ? REASONING_MODEL : CHAT_MODEL;
                    // Stream tokens; flush complete sentences to text + TTS as they arrive.
                    StringBuilder full = new StringBuilder();
                    StringBuilder pending = new StringBuilder();
                    callLLMStreaming(model, fInstruction, history, delta -> {
                        if (cancelled.get()) return false;   // stop reading
                        full.append(delta);
                        pending.append(delta);
                        flushSentences(pending, false);
                        return true;
                    });
                    if (cancelled.get()) return;
                    flushSentences(pending, true);           // flush remainder
                    String reply = full.toString().trim();
                    if (!reply.isEmpty()) {
                        history.add(Map.of("role", "assistant", "content", reply));
                        trimHistory();
                    }
                    send(event("done", ""));
                } catch (Exception e) {
                    if (!cancelled.get()) send(error("LLM: " + e.getMessage()));
                }
            });
        }

        // Split buffered text into complete sentences (delegates to TextUtil so
        // the logic is unit-tested). Emits + speaks each; keeps the tail buffered.
        void flushSentences(StringBuilder buf, boolean force) {
            String[] rem = new String[1];
            List<String> sentences = TextUtil.flushSentences(buf.toString(), force, rem);
            for (String s : sentences) emitSentence(s);
            buf.setLength(0);
            buf.append(rem[0] == null ? "" : rem[0]);
        }

        void emitSentence(String sentence) {
            if (cancelled.get()) return;
            send(event("assistant_sentence", sentence));
            speak(sentence);
        }

        // Transcribes audio using the resolved language. Automatic BG/EN
        // detection is unreliable with Whisper (forcing bg always yields Cyrillic,
        // forcing en yields fluent English even for Bulgarian speech), so we use
        // the mode default or the user's manual 🎙 language toggle.
        String transcribe(byte[] wav) throws Exception {
            return transcribeLang(wav, sttLang());
        }

        String transcribeLang(byte[] wav, String lang) throws Exception {
            String boundary = "----VoiceAI" + Long.toHexString(System.nanoTime()) + "Boundary";
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(wav);
            String fields = "\r\n--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"language\"\r\n\r\n" + lang + "\r\n" +
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n";
            body.write(fields.getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create(STT_URL))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
            return JSON.readTree(resp.body()).path("text").asText("").trim();
        }

        void speak(String reply) {
            if (cancelled.get() || reply.isBlank()) return;
            try {
                byte[] wav = ttsWav(reply);
                if (wav.length == 0 || cancelled.get()) return;
                int seq = audioSeq++;
                ObjectNode o = event("audio_reply", "");
                o.put("size", wav.length);
                o.put("seq", seq);
                send(o);
                WebSockets.sendBinary(ByteBuffer.wrap(wav), channel, null);
            } catch (Exception e) {
                if (!cancelled.get()) send(error("TTS: " + e.getMessage()));
            }
        }

        byte[] ttsWav(String text) throws Exception {
            return ttsWav(text, 1.0, null);
        }

        // speed > 1.0 slows speech (Piper length_scale); enVoice overrides the
        // English Piper voice stem (e.g. "en_US-lessac-medium").
        byte[] ttsWav(String text, double speed, String enVoice) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(ESPEAK_BIN, "-v", ESPEAK_VOICE, "--stdin", "--stdout");
            Map<String,String> env = pb.environment();
            if (speed > 0) env.put("PIPER_LENGTH_SCALE", String.valueOf(speed));
            if (enVoice != null && !enVoice.isEmpty()) env.put("PIPER_EN_VOICE", enVoice);
            Process p = pb.start();
            ttsProc = p;
            p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            byte[] wav;
            try (InputStream is = p.getInputStream()) {
                wav = is.readAllBytes();
            }
            p.waitFor();
            ttsProc = null;
            return wav;
        }

        // Ask for a concise Bulgarian translation. Checks a local dictionary of
        // common words first (accurate, no Russian slips); LLM is the fallback.
        void translate(String phrase) {
            if (phrase.isEmpty()) return;
            String local = Dictionary.lookup(phrase);
            if (local != null) {
                ObjectNode o = event("translation", "");
                o.put("phrase", phrase);
                o.put("value", local);
                o.put("source", "dictionary");
                send(o);
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    String sys = "You are an English→Bulgarian dictionary. Give the most common Bulgarian " +
                            "translation of the English word or phrase the user sends. Reply with ONLY the " +
                            "Bulgarian word(s) in correct Bulgarian (never Russian), no quotes, no English, " +
                            "no explanation. Examples: 'ask' -> питам; 'weather' -> време; 'run' -> тичам.";
                    List<Map<String,String>> one =
                            List.of(Map.of("role", "user", "content", phrase));
                    StringBuilder sb = new StringBuilder();
                    callLLMStreaming(CHAT_MODEL, sys, one, 0.2, d -> { sb.append(d); return true; });
                    ObjectNode o = event("translation", "");
                    o.put("phrase", phrase);
                    o.put("value", sb.toString().trim());
                    o.put("source", "llm");
                    send(o);
                } catch (Exception e) {
                    send(error("Translate: " + e.getMessage()));
                }
            });
        }

        // Re-speak arbitrary text (e.g. slower). Sends a tagged audio_reply so the
        // client can play it as a one-off without disturbing the main queue.
        void reSpeak(String text, double speed, int id) {
            if (text.isEmpty()) return;
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] wav = ttsWav(text, speed, null);
                    if (wav.length == 0) return;
                    int seq = audioSeq++;
                    ObjectNode o = event("audio_reply", "");
                    o.put("size", wav.length);
                    o.put("seq", seq);
                    o.put("oneShot", true);
                    if (id >= 0) o.put("id", id);
                    send(o);
                    WebSockets.sendBinary(ByteBuffer.wrap(wav), channel, null);
                } catch (Exception e) {
                    send(error("TTS: " + e.getMessage()));
                }
            });
        }

        // ---- Pronunciation practice ----
        // Generate a target phrase for the learner's level, store it, speak it.
        void practiceNext() {
            CompletableFuture.runAsync(() -> {
                try {
                    String lvl = difficulty.isEmpty() ? "A2" : difficulty;
                    String sys = "Generate ONE short English sentence for pronunciation practice at CEFR " +
                            lvl + " level (4-9 words). Reply with ONLY the sentence, no quotes, no extra text.";
                    StringBuilder sb = new StringBuilder();
                    callLLMStreaming(CHAT_MODEL, sys,
                            List.of(Map.of("role","user","content","Give me a sentence.")), 0.8,
                            d -> { sb.append(d); return true; });
                    practiceTarget = sb.toString().replaceAll("\\s+", " ").trim()
                            .replaceAll("^[\"']|[\"']$", "");
                    if (practiceTarget.isEmpty()) practiceTarget = "The quick brown fox jumps over the lazy dog.";
                    ObjectNode o = event("practice_target", practiceTarget);
                    send(o);
                    reSpeak(practiceTarget, 1.0, -1);   // speak the target
                } catch (Exception e) {
                    send(error("Practice: " + e.getMessage()));
                }
            });
        }

        // Compare what the user said against the target, word by word.
        void scorePronunciation(String heard) {
            String[] target = practiceTarget.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9'\\s]", "").trim().split("\\s+");
            java.util.Set<String> heardSet = new java.util.HashSet<>(Arrays.asList(
                    heard.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9'\\s]", "").trim().split("\\s+")));
            var words = JSON.createArrayNode();
            int ok = 0;
            for (String w : target) {
                if (w.isEmpty()) continue;
                boolean correct = heardSet.contains(w);
                if (correct) ok++;
                ObjectNode wo = JSON.createObjectNode();
                wo.put("word", w);
                wo.put("ok", correct);
                words.add(wo);
            }
            int total = words.size();
            int score = total == 0 ? 0 : (int) Math.round(100.0 * ok / total);
            ObjectNode o = event("practice_result", "");
            o.put("heard", heard);
            o.put("target", practiceTarget);
            o.put("score", score);
            o.set("words", words);
            send(o);
        }

        // ---- Grammar drill ----
        // Generate an exercise (with a hidden expected answer) for the level.
        void drillNext() {
            CompletableFuture.runAsync(() -> {
                try {
                    String lvl = difficulty.isEmpty() ? "A2" : difficulty;
                    String sys = "Create ONE short English grammar exercise for CEFR " + lvl + ". " +
                            "Either a fill-in-the-blank or a 'correct the mistake' task. " +
                            "Return STRICT JSON only: {\"question\":\"...\",\"answer\":\"...\",\"topic\":\"...\"}. " +
                            "Keep the question one line. No extra text.";
                    StringBuilder sb = new StringBuilder();
                    callLLMStreaming(CHAT_MODEL, sys,
                            List.of(Map.of("role","user","content","New exercise.")), 0.8,
                            d -> { sb.append(d); return true; });
                    String raw = sb.toString().trim();
                    int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                    String q = "", topic = "";
                    if (a >= 0 && b > a) {
                        try {
                            JsonNode j = JSON.readTree(raw.substring(a, b + 1));
                            q = j.path("question").asText("");
                            drillAnswer = j.path("answer").asText("");
                            topic = j.path("topic").asText("");
                        } catch (Exception ignore) {}
                    }
                    if (q.isEmpty()) { q = "Fill in: She ___ (go) to school every day."; drillAnswer = "goes"; topic = "present simple"; }
                    ObjectNode o = event("drill_question", q);
                    o.put("topic", topic);
                    send(o);
                } catch (Exception e) {
                    send(error("Drill: " + e.getMessage()));
                }
            });
        }

        // Check the user's answer against the expected one, with a short explanation.
        void drillCheck(String userAnswer) {
            if (userAnswer.isEmpty()) return;
            final String expected = drillAnswer;
            CompletableFuture.runAsync(() -> {
                try {
                    boolean exact = normalize(userAnswer).equals(normalize(expected));
                    String sys = "You are an English teacher. The expected answer is: \"" + expected + "\". " +
                            "The student answered: \"" + userAnswer + "\". Say if the student is correct. " +
                            "Reply with a short verdict (Correct/Not quite), the correct answer, and a one-line " +
                            "explanation in Bulgarian. Keep it under 3 lines.";
                    StringBuilder sb = new StringBuilder();
                    callLLMStreaming(CHAT_MODEL, sys,
                            List.of(Map.of("role","user","content","Check my answer.")), 0.3,
                            d -> { sb.append(d); return true; });
                    ObjectNode o = event("drill_result", sb.toString().trim());
                    o.put("correct", exact);
                    o.put("expected", expected);
                    send(o);
                } catch (Exception e) {
                    send(error("Drill: " + e.getMessage()));
                }
            });
        }

        String normalize(String s) {
            return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9']", "").trim();
        }

        // ---- Dictation (listening) ----
        // Generate a sentence, speak it, but DO NOT reveal the text.
        void dictationNext() {
            CompletableFuture.runAsync(() -> {
                try {
                    String lvl = difficulty.isEmpty() ? "A2" : difficulty;
                    String sys = "Generate ONE natural English sentence for a listening dictation at CEFR " +
                            lvl + " level (5-12 words). Reply with ONLY the sentence, no quotes.";
                    StringBuilder sb = new StringBuilder();
                    callLLMStreaming(CHAT_MODEL, sys,
                            List.of(Map.of("role","user","content","Give me a sentence.")), 0.8,
                            d -> { sb.append(d); return true; });
                    dictationTarget = sb.toString().replaceAll("\\s+", " ").trim()
                            .replaceAll("^[\"']|[\"']$", "");
                    if (dictationTarget.isEmpty()) dictationTarget = "She usually drinks coffee in the morning.";
                    send(event("dict_ready", "")); // signal a new item (text stays hidden)
                    reSpeak(dictationTarget, 1.0, -1);
                } catch (Exception e) {
                    send(error("Dictation: " + e.getMessage()));
                }
            });
        }

        // Compare typed text against the hidden dictation target, reveal it.
        void dictationCheck(String typed) {
            String[] target = dictationTarget.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9'\\s]", "").trim().split("\\s+");
            java.util.Set<String> typedSet = new java.util.HashSet<>(Arrays.asList(
                    typed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9'\\s]", "").trim().split("\\s+")));
            var words = JSON.createArrayNode();
            int ok = 0;
            for (String w : target) {
                if (w.isEmpty()) continue;
                boolean correct = typedSet.contains(w);
                if (correct) ok++;
                ObjectNode wo = JSON.createObjectNode();
                wo.put("word", w); wo.put("ok", correct);
                words.add(wo);
            }
            int total = words.size();
            int score = total == 0 ? 0 : (int) Math.round(100.0 * ok / total);
            ObjectNode o = event("dict_result", "");
            o.put("typed", typed);
            o.put("target", dictationTarget);
            o.put("score", score);
            o.set("words", words);
            send(o);
        }

        // ---- Role-play (speaking) ----
        void roleplayStart(String scenario) {
            String role;
            switch (scenario) {
                case "restaurant":
                    role = "You are a friendly waiter at a restaurant. Stay in character. Greet the customer, " +
                           "take their order, make small talk. Keep replies to 1-2 short spoken sentences.";
                    break;
                case "airport":
                    role = "You are a check-in agent at an airport. Stay in character. Ask for passport, " +
                           "destination, luggage. Keep replies to 1-2 short spoken sentences.";
                    break;
                case "interview":
                    role = "You are a job interviewer. Stay in character. Ask common interview questions one at " +
                           "a time and react to answers. Keep replies to 1-2 short spoken sentences.";
                    break;
                case "shopping":
                    role = "You are a shop assistant in a clothing store. Stay in character. Help the customer " +
                           "find items, sizes, prices. Keep replies to 1-2 short spoken sentences.";
                    break;
                default:
                    role = "You are a friendly barista at a cafe. Stay in character. Take the order and chat " +
                           "briefly. Keep replies to 1-2 short spoken sentences.";
            }
            roleplayPrompt = "Speak only in English. " + role +
                    " If the learner makes a big mistake, gently rephrase correctly, then continue. " +
                    "Never break character or mention that you are an AI.";
            history.clear();
            // Kick off the scene with an in-character greeting.
            ask("(The learner has just arrived. Greet them and start the scene.)");
        }

        static byte[] pcmToWav(byte[] pcm) {
            int sr = 16000, channels = 1, bits = 16;
            int byteRate = sr * channels * bits / 8, blockAlign = channels * bits / 8;
            ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
            bb.putInt(36 + pcm.length);
            bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
            bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
            bb.putInt(16);
            bb.putShort((short) 1);
            bb.putShort((short) channels);
            bb.putInt(sr);
            bb.putInt(byteRate);
            bb.putShort((short) blockAlign);
            bb.putShort((short) bits);
            bb.put("data".getBytes(StandardCharsets.US_ASCII));
            bb.putInt(pcm.length);
            bb.put(pcm);
            return bb.array();
        }

        // Streams the completion. `onDelta` receives text chunks and returns
        // false to stop early (e.g. on interrupt). Uses OpenAI-style SSE.
        void callLLMStreaming(String model, String system, List<Map<String,String>> hist,
                              java.util.function.Predicate<String> onDelta) throws Exception {
            callLLMStreaming(model, system, hist, 0.7, onDelta);
        }

        void callLLMStreaming(String model, String system, List<Map<String,String>> hist,
                              double temperature,
                              java.util.function.Predicate<String> onDelta) throws Exception {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("stream", true);
            body.put("temperature", temperature);
            body.put("max_tokens", MAX_TOKENS);
            ObjectNode kwargs = body.putObject("chat_template_kwargs");
            kwargs.put("enable_thinking", false);

            var messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
            for (Map<String,String> m : hist) {
                ObjectNode x = messages.addObject();
                x.put("role", m.get("role"));
                x.put("content", m.get("content"));
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(LLM_URL))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("HTTP " + resp.statusCode() + ": " + err);
            }

            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (cancelled.get()) break;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    JsonNode node = JSON.readTree(data);
                    String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        if (!onDelta.test(delta)) break;
                    }
                }
            }
        }

        void send(JsonNode n) {
            WebSockets.sendText(n.toString(), channel, null);
        }
    }

    // A small, curated English→Bulgarian dictionary of common words. Used before
    // the LLM so frequent words are always translated correctly (no Russian slips).
    static class Dictionary {
        static final Map<String,String> MAP = new HashMap<>();
        static {
            String[][] pairs = {
                {"ask","питам"},{"answer","отговарям"},{"weather","време"},{"run","тичам"},
                {"walk","вървя"},{"eat","ям"},{"drink","пия"},{"sleep","спя"},{"read","чета"},
                {"write","пиша"},{"speak","говоря"},{"listen","слушам"},{"hear","чувам"},
                {"see","виждам"},{"look","гледам"},{"buy","купувам"},{"sell","продавам"},
                {"happy","щастлив"},{"sad","тъжен"},{"angry","ядосан"},{"tired","уморен"},
                {"hungry","гладен"},{"thirsty","жаден"},{"big","голям"},{"small","малък"},
                {"good","добър"},{"bad","лош"},{"fast","бърз"},{"slow","бавен"},
                {"hot","горещ"},{"cold","студен"},{"new","нов"},{"old","стар"},
                {"water","вода"},{"food","храна"},{"bread","хляб"},{"coffee","кафе"},
                {"tea","чай"},{"house","къща"},{"car","кола"},{"book","книга"},
                {"school","училище"},{"work","работа"},{"friend","приятел"},{"family","семейство"},
                {"child","дете"},{"man","мъж"},{"woman","жена"},{"day","ден"},
                {"night","нощ"},{"morning","сутрин"},{"today","днес"},{"tomorrow","утре"},
                {"yesterday","вчера"},{"year","година"},{"money","пари"},
                {"love","обичам"},{"like","харесвам"},{"want","искам"},{"need","нуждая се"},
                {"know","знам"},{"think","мисля"},{"understand","разбирам"},{"learn","уча"},
                {"teach","преподавам"},{"help","помагам"},{"give","давам"},{"take","вземам"},
                {"go","отивам"},{"come","идвам"},{"make","правя"},{"say","казвам"},
                {"tell","казвам"},{"open","отварям"},{"close","затварям"},
                {"start","започвам"},{"stop","спирам"},{"yes","да"},{"no","не"},
                {"please","моля"},{"thanks","благодаря"},{"hello","здравей"},{"goodbye","довиждане"},
                {"dog","куче"},{"cat","котка"},{"tree","дърво"},{"city","град"},
                {"country","държава"},{"language","език"},{"word","дума"},{"sentence","изречение"},
                {"question","въпрос"},{"name","име"},{"street","улица"},{"door","врата"},
                {"window","прозорец"},{"table","маса"},{"chair","стол"},{"phone","телефон"},
            };
            for (String[] p : pairs) MAP.put(p[0], p[1]);
        }
        // Returns the Bulgarian translation for a single known word, or null.
        static String lookup(String phrase) {
            if (phrase == null) return null;
            String key = phrase.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z'-]", "");
            if (key.isEmpty()) return null;
            return MAP.get(key);
        }
    }

    static class IntentRouter {
        static class Result {
            String intent; boolean reasoning; String systemPrompt;
            Result(String i, boolean r, String p) { intent=i; reasoning=r; systemPrompt=p; }
        }

        static Result route(String text, String mode) {
            String s = text.toLowerCase(Locale.ROOT);
            if ("teacher".equals(mode) || containsAny(s, "english", "английски", "grammar", "граматика",
                    "correct my", "practice english", "упражняваме")) {
                return new Result("ENGLISH_LEARNING", false, teacherPrompt());
            }
            if (containsAny(s, "step by step", "reason", "reasoning", "debug", "compiler",
                    "код", "code", "грешка", "защо пада", "анализирай")) {
                return new Result("REASONING", true, reasoningPrompt());
            }
            return new Result("GENERAL_CHAT", false, chatPrompt());
        }

        static boolean containsAny(String s, String... xs) {
            for (String x : xs) if (s.contains(x)) return true;
            return false;
        }

        static String base() {
            return "You are a local voice assistant for a Bulgarian user who is also learning English. " +
                    "Be natural and concise. The user may mix Bulgarian and English. " +
                    "When you write Bulgarian, use correct, natural Bulgarian only — never Russian words or spelling. " +
                    "When the user asks how to say a Bulgarian word/phrase in English (e.g. 'Как е на английски X', " +
                    "'как се казва X на английски'), reply with the correct English word/phrase directly, then a " +
                    "one short example. For example: 'Как е на английски здрасти?' -> \"Hi\" or \"Hello\". " +
                    "When the user asks to translate an English word into Bulgarian (e.g. 'преведи', 'какво значи'), " +
                    "give a short accurate Bulgarian translation first. Do not treat ordinary words as abbreviations. " +
                    "Otherwise, do not translate everything automatically; keep useful technical English terms as-is.";
        }

        static String chatPrompt() {
            return base() + "\nReply in the language the user uses. " +
                    "If Bulgarian and English are mixed, a mixed reply is acceptable.";
        }

        static String teacherPrompt() {
            return base() + "\nYou are an English teacher. Speak mainly in English. " +
                    "When the user asks for the meaning or translation of an English word, give the correct " +
                    "Bulgarian translation clearly (for example: 'ask' = 'питам / моля'), then a short English example. " +
                    "If the user makes an important English mistake, let them finish, give a short correction, " +
                    "explain in Bulgarian when necessary, then continue naturally. Do not correct every tiny mistake.";
        }

        static String reasoningPrompt() {
            return base() + "\nFor technical or difficult problems, reason carefully and provide a clear, " +
                    "structured answer. For voice, avoid unnecessary verbosity.";
        }
    }

    static ObjectNode event(String type, String value) {
        ObjectNode o = JSON.createObjectNode();
        o.put("type", type);
        if (!value.isEmpty()) o.put("value", value);
        return o;
    }

    static ObjectNode error(String message) {
        return event("error", message == null ? "unknown error" : message);
    }
}
