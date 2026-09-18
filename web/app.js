"use strict";

// ---------- DOM ----------
const chat = document.querySelector("#chat");
const statusEl = document.querySelector("#status");
const mode = document.querySelector("#mode");
const form = document.querySelector("#textForm");
const input = document.querySelector("#text");
const voiceBtn = document.querySelector("#voiceBtn");
const clearBtn = document.querySelector("#clearBtn");
const ttsBtn = document.querySelector("#ttsBtn");

const overlay = document.querySelector("#voiceOverlay");
const orb = document.querySelector("#orb");
const orbCtx = orb.getContext("2d");
const voState = document.querySelector("#voState");
const voCaption = document.querySelector("#voCaption");
const voMode = document.querySelector("#voMode");
const voClose = document.querySelector("#voClose");
const voMute = document.querySelector("#voMute");
const voInterrupt = document.querySelector("#voInterrupt");

// ---------- WebSocket ----------
let ws;
function send(obj){ if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); }

function connect(){
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => { setStatus("connected"); send({type:"config", mode:mode.value}); };
  ws.onclose = () => { setStatus("disconnected"); setTimeout(connect, 1500); };
  ws.onerror = () => setStatus("connection error");
  ws.onmessage = async e => {
    if (typeof e.data === "string") return handleMsg(JSON.parse(e.data));
    if (expectAudioSize > 0) return handleAudio(e.data);
  };
}
connect();

function setStatus(s){ statusEl.textContent = s; }

// ---------- Chat rendering + persistence ----------
const HISTORY_KEY = "voiceai.history.v1";
let historyLog = [];

function add(role, text, persist = true){
  const div = document.createElement("div");
  div.className = "msg " + role;
  div.textContent = text;
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
  if (persist && role !== "system"){
    historyLog.push({role, text});
    saveHistory();
  }
  return div;
}

function saveHistory(){
  try{ localStorage.setItem(HISTORY_KEY, JSON.stringify(historyLog.slice(-200))); }catch(e){}
}
function loadHistory(){
  try{
    const raw = localStorage.getItem(HISTORY_KEY);
    if (!raw) return;
    historyLog = JSON.parse(raw) || [];
    for (const m of historyLog) add(m.role, m.text, false);
    if (historyLog.length) add("system", "— restored previous conversation —", false);
  }catch(e){}
}
function clearHistory(){
  historyLog = [];
  try{ localStorage.removeItem(HISTORY_KEY); }catch(e){}
  chat.innerHTML = "";
}
loadHistory();

// ============================================================
// Voice mode state machine: idle -> listening -> thinking -> speaking -> listening
// ============================================================
const State = { OFF:"off", LISTENING:"listening", THINKING:"thinking", SPEAKING:"speaking" };
let vState = State.OFF;
let voiceMode = false;   // overlay open
let micMuted = false;

function setVState(s){
  vState = s;
  const labels = {
    [State.LISTENING]: "Listening…",
    [State.THINKING]: "Thinking…",
    [State.SPEAKING]: "Speaking…",
    [State.OFF]: "Tap the orb and speak",
  };
  voState.textContent = micMuted && s === State.LISTENING ? "Muted" : labels[s];
}

// ---------- TTS routing ----------
// Piper (server WAV) sounds far better and more consistent than the browser's
// built-in voices (which are often robotic eSpeak voices, especially for
// Bulgarian). So we PREFER Piper and only use the browser voice if explicitly
// switched. Toggle with the 🔊 button.
let useServerTTS = true;                 // default: Piper
let synthOk = false, currentVoice = null;
if ("speechSynthesis" in window){
  const pick = () => {
    const voices = speechSynthesis.getVoices();
    if (!voices.length) return;
    synthOk = true;
    currentVoice =
      voices.find(v => /en/i.test(v.lang) && v.localService === false) ||
      voices.find(v => /en/i.test(v.lang)) || voices[0];
  };
  speechSynthesis.onvoiceschanged = pick; pick();
}
// True when we should render/play the server's Piper WAV.
function serverTTSActive(){ return useServerTTS || !synthOk; }

let speakingCount = 0;
function pickVoiceForText(text){
  if (!("speechSynthesis" in window)) return currentVoice;
  const voices = speechSynthesis.getVoices();
  const cyrillic = /[\u0400-\u04FF]/.test(text);
  if (cyrillic){
    return voices.find(v => /bg/i.test(v.lang)) ||
           voices.find(v => /ru|uk/i.test(v.lang)) || currentVoice;
  }
  return currentVoice;
}
function speakText(text){
  if (serverTTSActive()) return;            // Piper WAV will play instead
  if (!synthOk || !("speechSynthesis" in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  const v = pickVoiceForText(text);
  if (v) u.voice = v;
  u.rate = 1.05;
  u.onstart = startAiSim;
  u.onend = () => { stopAiSim(); onSpokenChunkDone(); };
  u.onerror = () => { stopAiSim(); onSpokenChunkDone(); };
  u.onboundary = () => { aiSimBurst = 1; }; // pulse on each word boundary
  speakingCount++;
  if (voiceMode) setVState(State.SPEAKING);
  speechSynthesis.speak(u);
}

// Native speechSynthesis exposes no audio stream, so approximate the mouth
// movement: a gentle oscillation with word-boundary bursts drives the orb.
let aiSimTimer = null, aiSimPhase = 0, aiSimBurst = 0;
function startAiSim(){
  if (aiSimTimer) return;
  aiSimTimer = setInterval(() => {
    aiSimPhase += 0.35;
    const base = 0.35 + 0.25 * Math.abs(Math.sin(aiSimPhase))
               + 0.15 * Math.abs(Math.sin(aiSimPhase * 2.7));
    aiLevel = Math.min(1, base + aiSimBurst);
    aiSimBurst *= 0.6;
  }, 50);
}
function stopAiSim(){
  if (aiSimTimer){ clearInterval(aiSimTimer); aiSimTimer = null; }
  aiLevel = 0;
}
function onSpokenChunkDone(){
  speakingCount = Math.max(0, speakingCount - 1);
  if (speakingCount === 0) afterSpeaking();
}
function afterSpeaking(){
  if (!voiceMode) { setStatus("idle"); return; }
  // Resume listening for the next turn (hands-free).
  startListening();
}

// ---------- Server messages ----------
function handleMsg(m){
  switch (m.type){
    case "ready": break;
    case "intent": setStatus(m.value); break;
    case "status": setStatus(m.value); break;
    case "transcript":
      add("user", m.value);
      if (voiceMode){ voCaption.textContent = m.value; setVState(State.THINKING); }
      break;
    case "assistant_sentence":
      add("ai", m.value);
      if (voiceMode) voCaption.textContent = m.value;
      speakText(m.value);
      break;
    case "done":
      if (serverTTSActive() && voiceMode) setVState(State.SPEAKING);
      break;
    case "interrupted":
      setStatus("idle");
      if (voiceMode && !micMuted) startListening();
      break;
    case "error":
      add("system", "ERROR: " + m.value);
      setStatus("error");
      if (voiceMode) setVState(State.LISTENING);
      break;
    case "audio_reply":
      if (!serverTTSActive()){ if (voiceMode) setVState(State.SPEAKING); break; }
      // A new WAV chunk is coming (one per sentence). Collect its binary body.
      expectAudioSize = m.size || 0; audioBuf = [];
      if (voiceMode) setVState(State.SPEAKING);
      break;
    case "audio_done":
      // With streaming there is no single audio_done per chunk; playback queue
      // drives afterSpeaking() when the last chunk finishes.
      break;
  }
}

// ---------- Server WAV playback: sequential queue of streamed chunks ----------
let expectAudioSize = 0, audioBuf = [], currentAudio = null;
let audioQueue = [], playing = false;

function handleAudio(buf){
  audioBuf.push(new Uint8Array(buf));
  const total = audioBuf.reduce((n,b)=>n+b.length,0);
  if (expectAudioSize > 0 && total >= expectAudioSize){
    const blob = new Blob(audioBuf, {type:"audio/wav"});
    audioQueue.push(blob);
    expectAudioSize = 0; audioBuf = [];
    if (!playing) playNext();
  }
}

function playNext(){
  if (!audioQueue.length){
    playing = false;
    stopPlaybackAnalyser();
    afterSpeaking();          // whole response finished speaking
    return;
  }
  playing = true;
  const blob = audioQueue.shift();
  const url = URL.createObjectURL(blob);
  currentAudio = new Audio(url);
  currentAudio.crossOrigin = "anonymous";
  attachPlaybackAnalyser(currentAudio);
  currentAudio.onended = currentAudio.onerror = () => {
    URL.revokeObjectURL(url); currentAudio = null;
    playNext();               // chain to next sentence chunk
  };
  if (voiceMode) setVState(State.SPEAKING);
  currentAudio.play().catch(() => playNext());
}

// Feed server WAV playback through an analyser so the orb reflects the
// AI's actual speech amplitude.
let playCtx = null, playAnalyser = null, playRAF = null, playSrcNode = null;
function attachPlaybackAnalyser(audioEl){
  try{
    playCtx = playCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (playCtx.state === "suspended") playCtx.resume();
    // Release any previous element's source node first.
    try{ if (playSrcNode) playSrcNode.disconnect(); }catch(e){}
    playSrcNode = playCtx.createMediaElementSource(audioEl);
    if (!playAnalyser){
      playAnalyser = playCtx.createAnalyser();
      playAnalyser.fftSize = 256;
      playAnalyser.connect(playCtx.destination);
    }
    playSrcNode.connect(playAnalyser);
    const data = new Uint8Array(playAnalyser.frequencyBinCount);
    if (!playRAF){
      const tick = () => {
        if (!playAnalyser) return;
        playAnalyser.getByteTimeDomainData(data);
        let sum = 0;
        for (let i=0;i<data.length;i++){ const d=(data[i]-128)/128; sum += d*d; }
        aiLevel = Math.min(1, Math.sqrt(sum/data.length) * 3);
        playRAF = requestAnimationFrame(tick);
      };
      tick();
    }
  }catch(e){ /* fall back to no visualization */ }
}
function stopPlaybackAnalyser(){
  if (playRAF) cancelAnimationFrame(playRAF);
  playRAF = null; aiLevel = 0;
  try{ if (playSrcNode) playSrcNode.disconnect(); if (playAnalyser) playAnalyser.disconnect(); }catch(e){}
  playSrcNode = null; playAnalyser = null;
}
function stopPlayback(){
  if ("speechSynthesis" in window) speechSynthesis.cancel();
  stopAiSim();
  audioQueue = [];
  playing = false;
  if (currentAudio){ try{ currentAudio.pause(); currentAudio.onended = null; }catch(e){} currentAudio = null; }
  stopPlaybackAnalyser();
  speakingCount = 0;
  expectAudioSize = 0; audioBuf = [];
}

// ============================================================
// Audio capture + VAD (continuous, with barge-in)
// ============================================================
let audioCtx, streamRef, workletNode, analyser;
let capturing = false;
let speechMs = 0, silenceMs = 0, listenStart = 0, sentThisTurn = false;

// Thresholds
const VAD_SPEECH = 0.012;   // energy above this = speech
const MIN_SPEECH_MS = 250;  // must speak at least this long
const END_SILENCE_MS = 900; // silence after speech ends the turn
const BARGE_SPEECH_MS = 180; // speech while AI talks triggers barge-in

async function ensureAudio(){
  if (audioCtx) return;
  const stream = await navigator.mediaDevices.getUserMedia({
    audio:{echoCancellation:true, noiseSuppression:true, autoGainControl:true, channelCount:1}
  });
  streamRef = stream;
  audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  await audioCtx.audioWorklet.addModule("/worklet.js");
  const src = audioCtx.createMediaStreamSource(stream);
  workletNode = new AudioWorkletNode(audioCtx, "pcm16-recorder");
  analyser = audioCtx.createAnalyser();
  analyser.fftSize = 256;
  src.connect(analyser);
  src.connect(workletNode);
  const sink = audioCtx.createMediaStreamDestination();
  workletNode.connect(sink);

  workletNode.port.onmessage = e => {
    const d = e.data;
    if (typeof d.level === "number"){ onLevel(d.level, d.ms); return; }
    // PCM16 chunk — only forward while actively capturing a user turn
    if (capturing && !micMuted){
      const bytes = new Uint8Array(d.buffer);
      for (let off = 0; off < bytes.length; off += 4096){
        ws.send(bytes.subarray(off, off + 4096));
      }
    }
  };
}

function startListening(){
  if (!voiceMode || micMuted) return;
  ensureAudio().then(() => {
    if (audioCtx.state === "suspended") audioCtx.resume();
    stopPlayback();
    setVState(State.LISTENING);
    beginTurn();
  }).catch(err => add("system", "Microphone error: " + err.message));
}

function beginTurn(){
  send({type:"audio_start"});
  capturing = true;
  sentThisTurn = false;
  speechMs = 0; silenceMs = 0; listenStart = Date.now();
}

function endTurn(){
  if (!capturing) return;
  capturing = false;
  send({type:"audio_end"});
  setVState(State.THINKING);
  setStatus("transcribing…");
}

// VAD driven by worklet energy frames
function onLevel(level, ms){
  drawOrb(level);
  if (!voiceMode || micMuted) return;

  // Barge-in: user speaks while AI is speaking → interrupt and start a new turn.
  if (vState === State.SPEAKING){
    if (level > VAD_SPEECH){
      bargeSpeech += ms;
      if (bargeSpeech > BARGE_SPEECH_MS){
        bargeSpeech = 0;
        stopPlayback();
        send({type:"interrupt"});
        startListening();
      }
    } else {
      bargeSpeech = Math.max(0, bargeSpeech - ms);
    }
    return;
  }

  if (!capturing) return;

  if (level > VAD_SPEECH){ speechMs += ms; silenceMs = 0; }
  else { silenceMs += ms; }

  // End of user turn: had speech, then a pause.
  if (speechMs > MIN_SPEECH_MS && silenceMs > END_SILENCE_MS &&
      Date.now() - listenStart > 700){
    endTurn();
  }
}
let bargeSpeech = 0;

// ---------- Orb rendering ----------
let orbPhase = 0, orbLevel = 0, aiLevel = 0;
function drawOrb(level){
  // While the AI speaks, drive the orb from the AI amplitude; otherwise mic.
  const target = (vState === State.SPEAKING) ? aiLevel : Math.min(1, level * 6);
  orbLevel = orbLevel * 0.8 + target * 0.2; // smooth
}
function orbColors(){
  switch (vState){
    case State.LISTENING: return ["#10a37f","#1fd6a6"];
    case State.THINKING:  return ["#6ea8ff","#9ec2ff"];
    case State.SPEAKING:  return ["#c084fc","#e9d5ff"];
    default:              return ["#3a3a45","#5a5a68"];
  }
}
function renderOrb(){
  const w = orb.width, h = orb.height, cx = w/2, cy = h/2;
  orbCtx.clearRect(0,0,w,h);
  orbPhase += 0.03;
  const [c1,c2] = orbColors();
  const active = vState !== State.OFF;
  const pulse = active ? (0.06 + orbLevel*0.5) : 0.04;
  const baseR = w*0.28;

  // glow
  for (let i=3;i>=1;i--){
    const r = baseR*(1 + pulse*i*0.5) + Math.sin(orbPhase+i)*3;
    const g = orbCtx.createRadialGradient(cx,cy,r*0.2,cx,cy,r);
    g.addColorStop(0, c2 + "44");
    g.addColorStop(1, c1 + "00");
    orbCtx.fillStyle = g;
    orbCtx.beginPath(); orbCtx.arc(cx,cy,r,0,Math.PI*2); orbCtx.fill();
  }
  // core
  const r = baseR*(1 + pulse);
  const g = orbCtx.createRadialGradient(cx-r*0.3,cy-r*0.3,r*0.1,cx,cy,r);
  g.addColorStop(0, c2);
  g.addColorStop(1, c1);
  orbCtx.fillStyle = g;
  orbCtx.beginPath(); orbCtx.arc(cx,cy,r,0,Math.PI*2); orbCtx.fill();

  requestAnimationFrame(renderOrb);
}
requestAnimationFrame(renderOrb);

// ============================================================
// Voice mode open/close
// ============================================================
function openVoice(){
  voiceMode = true;
  micMuted = false;
  voMute.classList.remove("muted");
  voMode.textContent = mode.options[mode.selectedIndex].text;
  overlay.classList.remove("hidden");
  voCaption.textContent = "";
  startListening();
}
function closeVoice(){
  voiceMode = false;
  capturing = false;
  stopPlayback();
  send({type:"interrupt"});
  send({type:"audio_end"});
  setVState(State.OFF);
  overlay.classList.add("hidden");
  teardownAudio();
  setStatus("idle");
}
function teardownAudio(){
  try{
    if (workletNode) workletNode.disconnect();
    if (analyser) analyser.disconnect();
    if (streamRef) streamRef.getTracks().forEach(t=>t.stop());
    if (audioCtx) audioCtx.close();
  }catch(e){}
  audioCtx = null; streamRef = null; workletNode = null; analyser = null;
}

voiceBtn.onclick = openVoice;
voClose.onclick = closeVoice;
orb.onclick = () => { if (vState === State.OFF || micMuted) { micMuted=false; voMute.classList.remove("muted"); startListening(); } };

voMute.onclick = () => {
  micMuted = !micMuted;
  voMute.classList.toggle("muted", micMuted);
  if (micMuted){ capturing = false; setVState(State.LISTENING); }
  else startListening();
};

voInterrupt.onclick = () => {
  stopPlayback();
  send({type:"interrupt"});
  if (voiceMode && !micMuted) startListening();
};

// ---------- Text chat ----------
mode.onchange = () => {
  send({type:"config", mode:mode.value});
  voMode.textContent = mode.options[mode.selectedIndex].text;
};

form.onsubmit = e => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  add("user", text);
  send({type:"text", text});
  input.value = "";
};

clearBtn.onclick = () => {
  if (historyLog.length && !confirm("Clear the conversation?")) return;
  clearHistory();
};

ttsBtn.onclick = () => {
  useServerTTS = !useServerTTS;
  ttsBtn.textContent = useServerTTS ? "🔊 Piper" : "🔊 Browser";
  stopPlayback();
};

document.addEventListener("keydown", e => {
  if (e.key === "Escape" && voiceMode) closeVoice();
});

window.addEventListener("beforeunload", () => teardownAudio());
