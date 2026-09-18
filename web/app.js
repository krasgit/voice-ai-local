const chat = document.querySelector("#chat");
const statusEl = document.querySelector("#status");
const mode = document.querySelector("#mode");
const form = document.querySelector("#textForm");
const input = document.querySelector("#text");
const mic = document.querySelector("#mic");
const stop = document.querySelector("#stop");

let ws;
let audioCtx, source, proc;
let recording = false;
let expectAudioSize = 0;
let audioBuf = [];
let speechMs = 0;
let silenceMs = 0;
let recStart = 0;
let voiceConversation = false;
let restartTimer = null;

// Native device TTS (Web Speech API) is preferred; server espeak is the fallback.
let synthOk = false;
let currentVoice = null;
if ("speechSynthesis" in window) {
  const pickVoice = () => {
    const voices = speechSynthesis.getVoices();
    if (!voices.length) return;
    synthOk = true;
    let v = voices.find(x => x.lang && x.lang.toLowerCase().startsWith("en") && x.localService === false)
        || voices.find(x => x.lang && x.lang.toLowerCase().startsWith("en"))
        || voices[0];
    currentVoice = v;
    statusEl.textContent = "native voice: " + v.name;
    setTimeout(() => statusEl.textContent = "idle", 2000);
  };
  speechSynthesis.onvoiceschanged = pickVoice;
  pickVoice();
}

function speakText(text) {
  if (!synthOk || !("speechSynthesis" in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  if (currentVoice) u.voice = currentVoice;
  u.rate = 1.05;
  u.onend = u.onerror = onSpokenChunkDone;
  speakingCount++;
  speechSynthesis.speak(u);
}

let speakingCount = 0;
function onSpokenChunkDone() {
  speakingCount = Math.max(0, speakingCount - 1);
  if (speakingCount === 0) scheduleMicRestart();
}

function scheduleMicRestart() {
  if (restartTimer) return;
  if (!document.querySelector("#auto").checked || !voiceConversation) return;
  if (recording || expectAudioSize > 0) return;
  restartTimer = setTimeout(() => {
    restartTimer = null;
    if (!recording && document.querySelector("#auto").checked) {
      statusEl.textContent = "listening…";
      startRecording();
    }
  }, 900);
}

function add(role, text) {
  const div = document.createElement("div");
  div.className = "msg " + role;
  div.textContent = text;
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    statusEl.textContent = "connected";
    send({type:"config", mode:mode.value});
  };
  ws.onclose = () => {
    statusEl.textContent = "disconnected";
    setTimeout(connect, 1500);
  };
  ws.onerror = () => statusEl.textContent = "error";
  ws.onmessage = async e => {
    if (typeof e.data === "string") return handleMsg(JSON.parse(e.data));
    if (expectAudioSize > 0) return handleAudio(e.data);
  };
}
connect();

function handleMsg(m) {
  switch (m.type) {
    case "ready": add("system", m.value); break;
    case "intent": statusEl.textContent = m.value; break;
    case "assistant_sentence":
      add("ai", m.value);
      speakText(m.value);
      break;
    case "transcript": statusEl.textContent = "heard: " + m.value; add("user", m.value); voiceConversation = true; break;
    case "error": add("system", "ERROR: " + m.value); statusEl.textContent = "error"; break;
    case "interrupted": add("system", "Interrupted"); statusEl.textContent = "idle"; break;
    case "status": statusEl.textContent = m.value; break;
    case "audio_reply":
      if (synthOk) { statusEl.textContent = "speaking…"; break; } // server TTS not needed
      expectAudioSize = m.size || 0;
      audioBuf = [];
      statusEl.textContent = "speaking…";
      break;
    case "audio_done":
      statusEl.textContent = "idle";
      scheduleMicRestart();
      break;
  }
}

function handleAudio(buf) {
  audioBuf.push(new Uint8Array(buf));
  const total = audioBuf.reduce((n, b) => n + b.length, 0);
  if (expectAudioSize > 0 && total >= expectAudioSize) {
    const wav = new Blob(audioBuf, {type:"audio/wav"});
    const url = URL.createObjectURL(wav);
    const a = new Audio(url);
    a.onended = () => { URL.revokeObjectURL(url); scheduleMicRestart(); };
    a.play();
    expectAudioSize = 0;
    audioBuf = [];
    statusEl.textContent = "idle";
  }
}

mode.onchange = () => send({type:"config", mode:mode.value});

form.onsubmit = e => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  add("user", text);
  send({type:"text", text});
  input.value = "";
};

stop.onclick = () => {
  if (restartTimer) { clearTimeout(restartTimer); restartTimer = null; }
  if ("speechSynthesis" in window) speechSynthesis.cancel();
  send({type:"interrupt"});
};

mic.onclick = () => recording ? stopRecording() : startRecording();

async function startRecording() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {echoCancellation:true, noiseSuppression:true, channelCount:1}
    });
    send({type:"audio_start"});
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    await audioCtx.audioWorklet.addModule("/worklet.js");
    const streamSource = audioCtx.createMediaStreamSource(stream);
    proc = new AudioWorkletNode(audioCtx, "pcm16-recorder");
    speechMs = 0;
    silenceMs = 0;
    recStart = Date.now();
    proc.port.onmessage = e => {
      const d = e.data;
      if (typeof d.level === "number") { handleLevel(d.level, d.ms); return; }
      const i16 = d;
      const bytes = new Uint8Array(i16.buffer);
      for (let off = 0; off < bytes.length; off += 4096) {
        ws.send(bytes.subarray(off, off + 4096));
      }
    };
    const discard = audioCtx.createMediaStreamDestination();
    streamSource.connect(proc);
    proc.connect(discard);
    source = {stream, streamSource};
    source.stream.getTracks().forEach(t => t.addEventListener("ended", () => { if (recording) stopRecording(); }));
    recording = true;
    mic.textContent = "⏹ Stop microphone";
    statusEl.textContent = "recording…";
  } catch (e) {
    add("system", "Microphone error: " + e.message);
  }
}

function handleLevel(level, ms) {
  if (!recording) return;
  if (level > 0.006) {
    speechMs += ms;
    silenceMs = 0;
  } else {
    silenceMs += ms;
  }
  // Auto-stop after a pause so the answer comes without pressing the mic again.
  if (speechMs > 200 && silenceMs > 1400 && Date.now() - recStart > 1000) {
    stopRecording();
    statusEl.textContent = "transcribing…";
  }
}

function stopRecording() {
  recording = false;
  try {
    source.streamSource.disconnect();
    proc.disconnect();
    source.stream.getTracks().forEach(t => t.stop());
    audioCtx.close();
  } catch (e) {}
  send({type:"audio_end"});
  mic.textContent = "🎙 Start microphone";
  statusEl.textContent = "transcribing…";
}

window.addEventListener("beforeunload", () => {
  if (recording) {
    try { source.stream.getTracks().forEach(t => t.stop()); } catch (e) {}
  }
});