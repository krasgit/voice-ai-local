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
    static final String ESPEAK_BIN = System.getenv().getOrDefault("ESPEAK_BIN", "espeak-ng");
    static final String ESPEAK_VOICE = System.getenv().getOrDefault("ESPEAK_VOICE", "en-us");
    static final String CHAT_MODEL = System.getenv().getOrDefault("CHAT_MODEL", "qwen3-1.7b");
    static final String REASONING_MODEL = System.getenv().getOrDefault("REASONING_MODEL", "qwen3-14b");
    static final int MAX_TOKENS = Integer.parseInt(System.getenv().getOrDefault("MAX_TOKENS", "256"));

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

        Undertow.builder()
                .addHttpListener(PORT, "0.0.0.0")
                .setHandler(root)
                .build()
                .start();

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

        Session(WebSocketChannel c) { channel = c; }

        void handle(JsonNode n) {
            String type = n.path("type").asText("");
            switch (type) {
                case "config":
                    mode = n.path("mode").asText("chat");
                    send(event("status", "mode=" + mode));
                    break;
                case "text":
                case "transcript":
                    String text = n.path("text").asText("").trim();
                    if (!text.isEmpty()) ask(text);
                    break;
                case "interrupt":
                    cancelled.set(true);
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
                    ask(text);
                } catch (Exception e) {
                    send(error("STT: " + e.getMessage()));
                }
            });
        }

        void ask(String user) {
            cancelled.set(false);
            IntentRouter.Result r = IntentRouter.route(user, mode);
            String instruction = r.systemPrompt;
            history.add(Map.of("role", "user", "content", user));

            CompletableFuture.runAsync(() -> {
                try {
                    send(event("intent", r.intent));
                    String model = r.reasoning ? REASONING_MODEL : CHAT_MODEL;
                    String reply = callLLM(model, instruction, history);
                    if (cancelled.get()) return;
                    history.add(Map.of("role", "assistant", "content", reply));
                    sendTextStreaming(reply);
                    send(event("done", ""));
                    speak(reply);
                } catch (Exception e) {
                    if (!cancelled.get()) send(error("LLM: " + e.getMessage()));
                }
            });
        }

        String transcribe(byte[] wav) throws Exception {
            String boundary = "----VoiceAI" + Long.toHexString(System.nanoTime()) + "Boundary";
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(wav);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

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
                if (wav.length == 0) return;
                ObjectNode o = event("audio_reply", "");
                o.put("size", wav.length);
                send(o);
                WebSockets.sendBinary(ByteBuffer.wrap(wav), channel, null);
            } catch (Exception e) {
                send(error("TTS: " + e.getMessage()));
            }
        }

        static byte[] ttsWav(String text) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(ESPEAK_BIN, "-v", ESPEAK_VOICE, "--stdin", "--stdout");
            Process p = pb.start();
            p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            byte[] wav;
            try (InputStream is = p.getInputStream()) {
                wav = is.readAllBytes();
            }
            p.waitFor();
            return wav;
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

        String callLLM(String model, String system, List<Map<String,String>> hist) throws Exception {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            body.put("temperature", 0.7);
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
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());

            JsonNode root = JSON.readTree(resp.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        }

        void sendTextStreaming(String text) {
            // Sentence-level streaming gives the browser a natural place to start TTS.
            String[] parts = text.split("(?<=[.!?。！？])\\s+");
            for (String s : parts) {
                if (cancelled.get()) return;
                ObjectNode o = event("assistant_sentence", s.trim());
                send(o);
            }
        }

        void send(JsonNode n) {
            WebSockets.sendText(n.toString(), channel, null);
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
            return "You are a local voice assistant. Be natural and concise. " +
                    "The user may mix Bulgarian and English. Preserve technical English terms " +
                    "when useful. Do not translate everything automatically.";
        }

        static String chatPrompt() {
            return base() + "\nReply naturally in the language the user uses. " +
                    "If Bulgarian and English are mixed, mixed replies are acceptable.";
        }

        static String teacherPrompt() {
            return base() + "\nSpeak mainly in English. If the user makes an important English mistake, " +
                    "let them finish, give a short correction, explain in Bulgarian when necessary, " +
                    "then continue naturally. Do not correct every tiny mistake.";
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
