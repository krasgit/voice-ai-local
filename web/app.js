"use strict";

// ---------- DOM ----------
const chat = document.querySelector("#chat");
const statusEl = document.querySelector("#status");
const mode = document.querySelector("#mode");
const level = document.querySelector("#level");
const form = document.querySelector("#textForm");
const input = document.querySelector("#text");
const voiceBtn = document.querySelector("#voiceBtn");
const clearBtn = document.querySelector("#clearBtn");
const exportBtn = document.querySelector("#exportBtn");
const settingsBtn = document.querySelector("#settingsBtn");
const latencyEl = document.querySelector("#latency");

const mistakesBox = document.querySelector("#mistakes");
const mistakesList = document.querySelector("#mistakesList");
const mistakesClose = document.querySelector("#mistakesClose");

const settings = document.querySelector("#settings");
const settingsClose = document.querySelector("#settingsClose");
const ttsEngine = document.querySelector("#ttsEngine");
const enVoiceSel = document.querySelector("#enVoice");
const speedRange = document.querySelector("#speed");
const speedVal = document.querySelector("#speedVal");
const pttToggle = document.querySelector("#pttToggle");

const overlay = document.querySelector("#voiceOverlay");
const orb = document.querySelector("#orb");
const orbCtx = orb.getContext("2d");
const voState = document.querySelector("#voState");
const voCaption = document.querySelector("#voCaption");
const voMode = document.querySelector("#voMode");
const voClose = document.querySelector("#voClose");
const voMute = document.querySelector("#voMute");
const voPtt = document.querySelector("#voPtt");
const voInterrupt = document.querySelector("#voInterrupt");

// Learning panels
const practicePanel = document.querySelector("#practicePanel");
const pracNext = document.querySelector("#pracNext");
const pracHear = document.querySelector("#pracHear");
const pracSlow = document.querySelector("#pracSlow");
const pracRec = document.querySelector("#pracRec");
const pracTarget = document.querySelector("#pracTarget");
const pracResult = document.querySelector("#pracResult");

const drillPanel = document.querySelector("#drillPanel");
const drillNextBtn = document.querySelector("#drillNext");
const drillTopic = document.querySelector("#drillTopic");
const drillQuestion = document.querySelector("#drillQuestion");
const drillForm = document.querySelector("#drillForm");
const drillInput = document.querySelector("#drillInput");
const drillRec = document.querySelector("#drillRec");
const drillResult = document.querySelector("#drillResult");

// ---------- Settings (persisted) ----------
const SET_KEY = "voiceai.settings.v1";
const settingsState = Object.assign(
  { engine:"piper", enVoice:"en_US-ryan-high", speed:1.0, ptt:false, level:"" },
  loadJSON(SET_KEY, {})
);
function applySettingsToUI(){
  ttsEngine.value = settingsState.engine;
  enVoiceSel.value = settingsState.enVoice;
  speedRange.value = settingsState.speed;
  speedVal.textContent = Number(settingsState.speed).toFixed(1) + "×";
  pttToggle.checked = settingsState.ptt;
  level.value = settingsState.level || "";
}
function saveSettings(){ saveJSON(SET_KEY, settingsState); }
function loadJSON(k, d){ try{ return JSON.parse(localStorage.getItem(k)) || d; }catch(e){ return d; } }
function saveJSON(k, v){ try{ localStorage.setItem(k, JSON.stringify(v)); }catch(e){} }
applySettingsToUI();

function serverTTSActive(){ return settingsState.engine === "piper" || !synthOk; }

// ---------- WebSocket ----------
let ws;
function send(obj){ if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); }
function sendConfig(){ send({type:"config", mode:mode.value, difficulty:settingsState.level}); }

function connect(){
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => { setStatus("connected"); sendConfig(); };
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
let lastAiMsgEl = null;      // the DOM node of the latest AI message (for karaoke/tools)

function addSystem(text){ return renderMsg("system", text, false); }
function addUser(text){ historyLog.push({role:"user",text}); saveHistory(); return renderMsg("user", text); }
function addAi(text){ historyLog.push({role:"ai",text}); saveHistory(); return renderMsg("ai", text); }

function renderMsg(role, text, tools = (role==="ai")){
  const div = document.createElement("div");
  div.className = "msg " + role;
  if (role === "ai"){
    div.appendChild(buildWords(text));
    if (tools) div.appendChild(buildTools(text));
  } else {
    div.textContent = text;
  }
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
  if (role === "ai") lastAiMsgEl = div;
  return div;
}

// Wrap each word in a clickable span (for click-to-translate + karaoke).
function buildWords(text){
  const frag = document.createElement("span");
  const parts = text.split(/(\s+)/);
  for (const p of parts){
    if (/^\s+$/.test(p) || p === ""){ frag.appendChild(document.createTextNode(p)); continue; }
    const span = document.createElement("span");
    span.className = "w";
    span.textContent = p;
    span.onclick = ev => { ev.stopPropagation(); translateWord(p.replace(/[^\p{L}'-]/gu,""), ev.clientX, ev.clientY); };
    frag.appendChild(span);
  }
  return frag;
}

function buildTools(text){
  const bar = document.createElement("div");
  bar.className = "tools";
  const repeat = document.createElement("button");
  repeat.textContent = "🔊 Repeat";
  repeat.onclick = () => reSpeak(text, 1.0);
  const slower = document.createElement("button");
  slower.textContent = "🐢 Slower";
  slower.onclick = () => reSpeak(text, 1.4);
  bar.append(repeat, slower);
  return bar;
}

function saveHistory(){ saveJSON(HISTORY_KEY, historyLog.slice(-200)); }
function loadHistory(){
  historyLog = loadJSON(HISTORY_KEY, []);
  for (const m of historyLog){
    if (m.role === "user") renderMsg("user", m.text, false);
    else if (m.role === "ai") renderMsg("ai", m.text);
  }
  if (historyLog.length) addSystem("— restored previous conversation —");
}
function clearHistory(){ historyLog = []; try{ localStorage.removeItem(HISTORY_KEY); }catch(e){} chat.innerHTML = ""; }
loadHistory();

// ---------- Mistake / correction tracking (teacher mode) ----------
const MIST_KEY = "voiceai.mistakes.v1";
let mistakes = loadJSON(MIST_KEY, []);
function renderMistakes(){
  mistakesList.innerHTML = "";
  for (const m of mistakes.slice(-20)){
    const li = document.createElement("li");
    li.textContent = m;
    mistakesList.appendChild(li);
  }
  mistakesBox.classList.toggle("hidden", mistakes.length === 0);
}
// Heuristic: detect "X → Y" or "should be" style corrections in teacher replies.
function scanForCorrections(text){
  if (mode.value !== "teacher") return;
  const arrow = text.match(/["“']?([A-Za-z][\w' ]{1,40})["”']?\s*(?:→|->|should be|instead of|not)\s*["“']?([A-Za-z][\w' ]{1,40})["”']?/);
  if (arrow){
    const entry = `${arrow[1].trim()} → ${arrow[2].trim()}`;
    if (!mistakes.includes(entry)){ mistakes.push(entry); saveJSON(MIST_KEY, mistakes); renderMistakes(); }
  }
}
renderMistakes();

// ============================================================
// Voice mode state machine
// ============================================================
const State = { OFF:"off", LISTENING:"listening", THINKING:"thinking", SPEAKING:"speaking" };
let vState = State.OFF;
let voiceMode = false, micMuted = false;

function setVState(s){
  vState = s;
  const labels = {
    [State.LISTENING]: settingsState.ptt ? "Hold to talk" : "Listening…",
    [State.THINKING]: "Thinking…",
    [State.SPEAKING]: "Speaking…",
    [State.OFF]: "Tap the orb and speak",
  };
  voState.textContent = micMuted && s === State.LISTENING ? "Muted" : labels[s];
}

// ---------- Browser TTS (only when engine=browser) ----------
let synthOk = false, currentVoice = null;
if ("speechSynthesis" in window){
  const pick = () => {
    const voices = speechSynthesis.getVoices();
    if (!voices.length) return;
    synthOk = true;
    currentVoice = voices.find(v => /en/i.test(v.lang) && v.localService === false)
      || voices.find(v => /en/i.test(v.lang)) || voices[0];
  };
  speechSynthesis.onvoiceschanged = pick; pick();
}
let speakingCount = 0;
function pickVoiceForText(text){
  const voices = speechSynthesis.getVoices();
  if (/[\u0400-\u04FF]/.test(text))
    return voices.find(v => /bg/i.test(v.lang)) || voices.find(v => /ru|uk/i.test(v.lang)) || currentVoice;
  return currentVoice;
}
function speakText(text){
  if (serverTTSActive()) return;
  if (!synthOk) return;
  const u = new SpeechSynthesisUtterance(text);
  const v = pickVoiceForText(text); if (v) u.voice = v;
  u.rate = Math.min(2, Math.max(0.5, 1/settingsState.speed * 1.05));
  u.onstart = startAiSim;
  u.onend = () => { stopAiSim(); onSpokenChunkDone(); };
  u.onerror = () => { stopAiSim(); onSpokenChunkDone(); };
  u.onboundary = () => { aiSimBurst = 1; };
  speakingCount++;
  if (voiceMode) setVState(State.SPEAKING);
  speechSynthesis.speak(u);
}
let aiSimTimer = null, aiSimPhase = 0, aiSimBurst = 0;
function startAiSim(){ if (aiSimTimer) return; aiSimTimer = setInterval(() => {
  aiSimPhase += 0.35;
  aiLevel = Math.min(1, 0.35 + 0.25*Math.abs(Math.sin(aiSimPhase)) + 0.15*Math.abs(Math.sin(aiSimPhase*2.7)) + aiSimBurst);
  aiSimBurst *= 0.6;
}, 50); }
function stopAiSim(){ if (aiSimTimer){ clearInterval(aiSimTimer); aiSimTimer=null; } aiLevel = 0; }
function onSpokenChunkDone(){ speakingCount = Math.max(0, speakingCount-1); if (speakingCount===0 && responseComplete) afterSpeaking(); }

function afterSpeaking(){
  if (!voiceMode){ setStatus("idle"); return; }
  if (!micMuted && !settingsState.ptt) startListening();
  else setVState(State.LISTENING);
}

// ---------- Latency tracking ----------
let reqStart = 0, firstTokenAt = 0;
function markRequest(){ reqStart = performance.now(); firstTokenAt = 0; }
function markFirstToken(){
  if (!firstTokenAt && reqStart){
    firstTokenAt = performance.now();
    latencyEl.textContent = `first reply in ${((firstTokenAt-reqStart)/1000).toFixed(1)}s`;
  }
}

// ---------- Server messages ----------
let responseComplete = true;   // true when the server has sent 'done' for the turn
function handleMsg(m){
  switch (m.type){
    case "ready": break;
    case "intent": setStatus(m.value); break;
    case "status": setStatus(m.value); break;
    case "transcript":
      addUser(m.value);
      if (voiceMode){ voCaption.textContent = m.value; setVState(State.THINKING); }
      break;
    case "assistant_sentence":
      markFirstToken();
      addAi(m.value);
      scanForCorrections(m.value);
      if (voiceMode) voCaption.textContent = m.value;
      speakText(m.value);
      break;
    case "done":
      responseComplete = true;
      // If nothing is currently speaking/queued, resume now.
      if (serverTTSActive()){ if (!playing) afterSpeaking(); }
      else if (speakingCount === 0) afterSpeaking();
      break;
    case "translation":
      showTranslation(m.phrase, m.value);
      break;
    case "practice_target":
      practiceTarget = m.value;
      pracTarget.textContent = m.value;
      pracResult.innerHTML = "";
      break;
    case "practice_result":
      renderPracticeResult(m);
      break;
    case "drill_question":
      drillQuestion.textContent = m.value;
      drillTopic.textContent = m.topic ? ("topic: " + m.topic) : "";
      drillResult.innerHTML = "";
      drillInput.value = "";
      break;
    case "drill_result":
      drillResult.innerHTML = `<span class="${m.correct?'verdict-ok':'verdict-bad'}">${escapeHtml(m.value)}</span>`;
      break;
    case "interrupted":
      responseComplete = true;
      setStatus("idle");
      if (voiceMode && !micMuted && !settingsState.ptt) startListening();
      break;
    case "error":
      responseComplete = true;
      addSystem("ERROR: " + m.value);
      setStatus("error");
      if (voiceMode) setVState(State.LISTENING);
      break;
    case "audio_reply":
      if (!serverTTSActive()){ if (voiceMode) setVState(State.SPEAKING); break; }
      markFirstToken();
      expectAudioSize = m.size || 0; audioBuf = [];
      pendingOneShot = !!m.oneShot;
      if (voiceMode) setVState(State.SPEAKING);
      break;
  }
}

// ---------- Server WAV playback: sequential queue ----------
let expectAudioSize = 0, audioBuf = [], currentAudio = null;
let audioQueue = [], playing = false, pendingOneShot = false;

function handleAudio(buf){
  audioBuf.push(new Uint8Array(buf));
  const total = audioBuf.reduce((n,b)=>n+b.length,0);
  if (expectAudioSize > 0 && total >= expectAudioSize){
    const blob = new Blob(audioBuf, {type:"audio/wav"});
    expectAudioSize = 0; audioBuf = [];
    if (pendingOneShot){ pendingOneShot = false; playOneShot(blob); return; }
    audioQueue.push(blob);
    if (!playing) playNext();
  }
}

// One-shot playback (repeat/slower/word) — doesn't affect the main flow.
function playOneShot(blob){
  const url = URL.createObjectURL(blob);
  const a = new Audio(url);
  a.onended = a.onerror = () => URL.revokeObjectURL(url);
  a.play().catch(()=>{});
}

function playNext(){
  if (!audioQueue.length){
    playing = false;
    stopPlaybackAnalyser();
    if (responseComplete) afterSpeaking();   // only finish when server said done
    return;
  }
  playing = true;
  const blob = audioQueue.shift();
  const url = URL.createObjectURL(blob);
  currentAudio = new Audio(url);
  currentAudio.crossOrigin = "anonymous";
  attachPlaybackAnalyser(currentAudio);
  const sentenceText = lastAiMsgEl ? lastAiMsgEl.querySelector("span") : null;
  currentAudio.onloadedmetadata = () => startKaraoke(currentAudio.duration);
  currentAudio.onended = currentAudio.onerror = () => {
    URL.revokeObjectURL(url); currentAudio = null; stopKaraoke();
    playNext();
  };
  if (voiceMode) setVState(State.SPEAKING);
  currentAudio.play().catch(() => playNext());
}

// ---------- Karaoke word highlight ----------
let karaokeTimer = null;
function startKaraoke(duration){
  stopKaraoke();
  if (!lastAiMsgEl || !isFinite(duration) || duration <= 0) return;
  const words = [...lastAiMsgEl.querySelectorAll(".w")];
  if (!words.length) return;
  const per = (duration * 1000) / words.length;
  let i = 0;
  karaokeTimer = setInterval(() => {
    words.forEach(w => w.classList.remove("on"));
    if (i < words.length){ words[i].classList.add("on"); i++; }
    else stopKaraoke();
  }, per);
}
function stopKaraoke(){
  if (karaokeTimer){ clearInterval(karaokeTimer); karaokeTimer = null; }
  if (lastAiMsgEl) lastAiMsgEl.querySelectorAll(".w.on").forEach(w => w.classList.remove("on"));
}

// ---------- Playback analyser (orb amplitude) ----------
let playCtx = null, playAnalyser = null, playRAF = null, playSrcNode = null;
function attachPlaybackAnalyser(audioEl){
  try{
    playCtx = playCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (playCtx.state === "suspended") playCtx.resume();
    try{ if (playSrcNode) playSrcNode.disconnect(); }catch(e){}
    playSrcNode = playCtx.createMediaElementSource(audioEl);
    if (!playAnalyser){ playAnalyser = playCtx.createAnalyser(); playAnalyser.fftSize = 256; playAnalyser.connect(playCtx.destination); }
    playSrcNode.connect(playAnalyser);
    const data = new Uint8Array(playAnalyser.frequencyBinCount);
    if (!playRAF){
      const tick = () => {
        if (!playAnalyser) return;
        playAnalyser.getByteTimeDomainData(data);
        let sum = 0; for (let i=0;i<data.length;i++){ const d=(data[i]-128)/128; sum += d*d; }
        aiLevel = Math.min(1, Math.sqrt(sum/data.length) * 3);
        playRAF = requestAnimationFrame(tick);
      };
      tick();
    }
  }catch(e){}
}
function stopPlaybackAnalyser(){
  if (playRAF) cancelAnimationFrame(playRAF);
  playRAF = null; aiLevel = 0;
  try{ if (playSrcNode) playSrcNode.disconnect(); }catch(e){}
  playSrcNode = null;
}
function stopPlayback(){
  if ("speechSynthesis" in window) speechSynthesis.cancel();
  stopAiSim(); stopKaraoke();
  audioQueue = []; playing = false;
  if (currentAudio){ try{ currentAudio.pause(); currentAudio.onended = null; }catch(e){} currentAudio = null; }
  stopPlaybackAnalyser();
  speakingCount = 0; expectAudioSize = 0; audioBuf = [];
}

// ---------- Click-to-translate ----------
let popoverEl = null;
function translateWord(word, x, y){
  if (!word) return;
  showTranslation(word, "…", x, y);
  send({type:"translate", text:word});
}
function showTranslation(phrase, value, x, y){
  if (popoverEl) popoverEl.remove();
  popoverEl = document.createElement("div");
  popoverEl.className = "popover";
  popoverEl.innerHTML = `<div class="src">${escapeHtml(phrase)}</div><div class="dst">${escapeHtml(value)}</div>`;
  document.body.appendChild(popoverEl);
  const px = (x ?? window.innerWidth/2), py = (y ?? window.innerHeight/2);
  popoverEl.style.left = Math.min(px, window.innerWidth-260) + "px";
  popoverEl.style.top = (py + 12) + "px";
  clearTimeout(showTranslation._t);
  showTranslation._t = setTimeout(() => { if (popoverEl){ popoverEl.remove(); popoverEl=null; } }, 4000);
}
document.addEventListener("click", e => {
  if (popoverEl && !e.target.classList.contains("w") && !popoverEl.contains(e.target)){ popoverEl.remove(); popoverEl=null; }
});
function escapeHtml(s){ return String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

// ---------- Repeat / slower ----------
function reSpeak(text, speed){
  if (serverTTSActive()){
    send({type:"speak", text, speed});
  } else if (synthOk){
    const u = new SpeechSynthesisUtterance(text);
    const v = pickVoiceForText(text); if (v) u.voice = v;
    u.rate = Math.min(2, Math.max(0.4, 1/speed));
    speechSynthesis.speak(u);
  }
}

// ============================================================
// Audio capture + VAD (continuous, with barge-in) / push-to-talk
// ============================================================
let audioCtx, streamRef, workletNode, analyser;
let capturing = false;
let speechMs = 0, silenceMs = 0, listenStart = 0;
let bargeSpeech = 0;

const VAD_SPEECH = 0.012, MIN_SPEECH_MS = 250, END_SILENCE_MS = 900, BARGE_SPEECH_MS = 180;

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
  analyser = audioCtx.createAnalyser(); analyser.fftSize = 256;
  src.connect(analyser); src.connect(workletNode);
  workletNode.connect(audioCtx.createMediaStreamDestination());
  workletNode.port.onmessage = e => {
    const d = e.data;
    if (typeof d.level === "number"){ onLevel(d.level, d.ms); return; }
    if (capturing && !micMuted){
      const bytes = new Uint8Array(d.buffer);
      for (let off=0; off<bytes.length; off+=4096) ws.send(bytes.subarray(off, off+4096));
    }
  };
}

function startListening(){
  if (!voiceMode || micMuted) return;
  if (settingsState.ptt){ setVState(State.LISTENING); return; }   // wait for hold
  ensureAudio().then(() => {
    if (audioCtx.state === "suspended") audioCtx.resume();
    stopPlayback(); setVState(State.LISTENING); beginTurn();
  }).catch(err => addSystem("Microphone error: " + err.message));
}

function beginTurn(){
  send({type:"audio_start"}); capturing = true;
  speechMs = 0; silenceMs = 0; listenStart = Date.now();
}
function endTurn(){
  if (!capturing) return;
  capturing = false; send({type:"audio_end"});
  markRequest(); responseComplete = false;
  setVState(State.THINKING); setStatus("transcribing…");
}

// Push-to-talk: start/stop capture on button hold.
function pttStart(){
  if (!voiceMode || micMuted) return;
  ensureAudio().then(() => {
    if (audioCtx.state === "suspended") audioCtx.resume();
    stopPlayback(); setVState(State.LISTENING); beginTurn();
    voPtt.classList.add("talking");
  }).catch(err => addSystem("Microphone error: " + err.message));
}
function pttStop(){ if (capturing){ voPtt.classList.remove("talking"); endTurn(); } }

function onLevel(level, ms){
  drawOrb(level);
  if (!voiceMode || micMuted) return;

  // Barge-in only in continuous (non-PTT) mode, and ignore quiet echo of TTS.
  if (vState === State.SPEAKING){
    if (!settingsState.ptt && level > VAD_SPEECH*2.2){
      bargeSpeech += ms;
      if (bargeSpeech > BARGE_SPEECH_MS){ bargeSpeech = 0; stopPlayback(); send({type:"interrupt"}); startListening(); }
    } else bargeSpeech = Math.max(0, bargeSpeech - ms);
    return;
  }
  if (!capturing) return;
  if (level > VAD_SPEECH){ speechMs += ms; silenceMs = 0; } else silenceMs += ms;
  // Auto-end only in continuous mode.
  if (!settingsState.ptt && speechMs > MIN_SPEECH_MS && silenceMs > END_SILENCE_MS && Date.now()-listenStart > 700) endTurn();
}

// ---------- Orb rendering ----------
let orbPhase = 0, orbLevel = 0, aiLevel = 0;
function drawOrb(level){
  const target = (vState === State.SPEAKING) ? aiLevel : Math.min(1, level*6);
  orbLevel = orbLevel*0.8 + target*0.2;
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
  orbCtx.clearRect(0,0,w,h); orbPhase += 0.03;
  const [c1,c2] = orbColors();
  const active = vState !== State.OFF;
  const pulse = active ? (0.06 + orbLevel*0.5) : 0.04;
  const baseR = w*0.28;
  for (let i=3;i>=1;i--){
    const r = baseR*(1 + pulse*i*0.5) + Math.sin(orbPhase+i)*3;
    const g = orbCtx.createRadialGradient(cx,cy,r*0.2,cx,cy,r);
    g.addColorStop(0, c2+"44"); g.addColorStop(1, c1+"00");
    orbCtx.fillStyle = g; orbCtx.beginPath(); orbCtx.arc(cx,cy,r,0,Math.PI*2); orbCtx.fill();
  }
  const r = baseR*(1+pulse);
  const g = orbCtx.createRadialGradient(cx-r*0.3,cy-r*0.3,r*0.1,cx,cy,r);
  g.addColorStop(0,c2); g.addColorStop(1,c1);
  orbCtx.fillStyle = g; orbCtx.beginPath(); orbCtx.arc(cx,cy,r,0,Math.PI*2); orbCtx.fill();
  requestAnimationFrame(renderOrb);
}
requestAnimationFrame(renderOrb);

// ============================================================
// Voice mode open/close
// ============================================================
function openVoice(){
  voiceMode = true; micMuted = false;
  voMute.classList.remove("muted");
  voMode.textContent = mode.options[mode.selectedIndex].text;
  voPtt.classList.toggle("hidden", !settingsState.ptt);
  overlay.classList.remove("hidden"); voCaption.textContent = "";
  startListening();
}
function closeVoice(){
  voiceMode = false; capturing = false;
  stopPlayback(); send({type:"interrupt"}); send({type:"audio_end"});
  setVState(State.OFF); overlay.classList.add("hidden");
  teardownAudio(); setStatus("idle");
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
orb.onclick = () => { if ((vState === State.OFF || micMuted) && !settingsState.ptt){ micMuted=false; voMute.classList.remove("muted"); startListening(); } };
voMute.onclick = () => {
  micMuted = !micMuted; voMute.classList.toggle("muted", micMuted);
  if (micMuted){ capturing = false; setVState(State.LISTENING); } else startListening();
};
// Push-to-talk button: hold to talk.
voPtt.addEventListener("mousedown", pttStart);
voPtt.addEventListener("touchstart", e => { e.preventDefault(); pttStart(); }, {passive:false});
voPtt.addEventListener("mouseup", pttStop);
voPtt.addEventListener("mouseleave", pttStop);
voPtt.addEventListener("touchend", e => { e.preventDefault(); pttStop(); }, {passive:false});
voInterrupt.onclick = () => { stopPlayback(); send({type:"interrupt"}); if (voiceMode && !micMuted && !settingsState.ptt) startListening(); };

// ---------- Header controls ----------
mode.onchange = () => { sendConfig(); voMode.textContent = mode.options[mode.selectedIndex].text; updatePanels(); };

// Show the learning panel that matches the current mode (and hide the chat/voice
// affordances that don't apply). Chat is shown for chat/teacher.
function updatePanels(){
  const m = mode.value;
  practicePanel.classList.toggle("hidden", m !== "practice");
  drillPanel.classList.toggle("hidden", m !== "drill");
  // Voice-chat button is only meaningful in conversational modes.
  voiceBtn.classList.toggle("hidden", m === "practice" || m === "drill");
}
updatePanels();
level.onchange = () => { settingsState.level = level.value; saveSettings(); sendConfig(); };

form.onsubmit = e => {
  e.preventDefault();
  const text = input.value.trim(); if (!text) return;
  addUser(text); markRequest(); responseComplete = false;
  send({type:"text", text}); input.value = "";
};

clearBtn.onclick = () => { if (historyLog.length && !confirm("Clear the conversation?")) return; clearHistory(); };
mistakesClose.onclick = () => mistakesBox.classList.add("hidden");

exportBtn.onclick = () => {
  const lines = historyLog.map(m => (m.role==="user"?"You: ":"AI: ") + m.text);
  if (mistakes.length){ lines.push("", "Corrections:", ...mistakes.map(x=>"- "+x)); }
  const blob = new Blob([lines.join("\n")], {type:"text/plain"});
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "conversation.txt"; a.click();
  setTimeout(()=>URL.revokeObjectURL(a.href), 1000);
};

// ---------- Settings panel ----------
settingsBtn.onclick = () => settings.classList.remove("hidden");
settingsClose.onclick = () => settings.classList.add("hidden");
settings.addEventListener("click", e => { if (e.target === settings) settings.classList.add("hidden"); });
ttsEngine.onchange = () => { settingsState.engine = ttsEngine.value; saveSettings(); stopPlayback(); };
enVoiceSel.onchange = () => { settingsState.enVoice = enVoiceSel.value; saveSettings(); };
speedRange.oninput = () => { settingsState.speed = parseFloat(speedRange.value); speedVal.textContent = settingsState.speed.toFixed(1)+"×"; saveSettings(); };
pttToggle.onchange = () => { settingsState.ptt = pttToggle.checked; saveSettings(); voPtt.classList.toggle("hidden", !settingsState.ptt); setVState(vState); };

document.addEventListener("keydown", e => {
  if (e.key === "Escape"){ if (voiceMode) closeVoice(); settings.classList.add("hidden"); }
});
window.addEventListener("beforeunload", () => teardownAudio());

// ---------- Pronunciation practice + Grammar drill ----------
let practiceTarget = "";

function renderPracticeResult(m){
  practiceTarget = m.target || practiceTarget;
  const frag = document.createElement("div");
  const scoreEl = document.createElement("span");
  scoreEl.className = "score";
  scoreEl.textContent = `Score: ${m.score}%  `;
  frag.appendChild(scoreEl);
  (m.words || []).forEach(w => {
    const s = document.createElement("span");
    s.className = w.ok ? "wok" : "wbad";
    s.textContent = w.word + " ";
    frag.appendChild(s);
  });
  const heard = document.createElement("div");
  heard.style.color = "#888"; heard.style.fontSize = "13px"; heard.style.marginTop = "6px";
  heard.textContent = "heard: " + (m.heard || "");
  pracResult.innerHTML = "";
  pracResult.append(frag, heard);
}

// Simple one-shot panel recording (reuses the capture pipeline). Toggle with
// the mic button; sends audio_start ... chunks ... audio_end, server scores.
let panelRecording = false;
function panelRecStart(btn){
  ensureAudio().then(() => {
    if (audioCtx.state === "suspended") audioCtx.resume();
    panelRecording = true; capturing = true; micMuted = false;
    send({type:"audio_start"});
    if (btn) btn.classList.add("talking");
    setStatus("recording… press again to stop");
  }).catch(err => addSystem("Microphone error: " + err.message));
}
function panelRecStop(btn){
  if (!panelRecording) return;
  panelRecording = false; capturing = false;
  send({type:"audio_end"});
  if (btn) btn.classList.remove("talking");
  setStatus("scoring…");
}
function panelRecToggle(btn){ panelRecording ? panelRecStop(btn) : panelRecStart(btn); }

pracNext.onclick = () => { pracResult.innerHTML = ""; send({type:"practice_next"}); setStatus("preparing…"); };
pracHear.onclick = () => { if (practiceTarget) send({type:"practice_repeat", speed:1.0}); };
pracSlow.onclick = () => { if (practiceTarget) send({type:"practice_repeat", speed:1.4}); };
pracRec.onclick = () => panelRecToggle(pracRec);

drillNextBtn.onclick = () => { drillResult.innerHTML = ""; send({type:"drill_next"}); setStatus("preparing…"); };
drillForm.onsubmit = e => { e.preventDefault(); const t = drillInput.value.trim(); if (t) send({type:"drill_answer", text:t}); };
drillRec.onclick = () => panelRecToggle(drillRec);

// ---------- PWA service worker ----------
if ("serviceWorker" in navigator){
  navigator.serviceWorker.register("/sw.js").catch(()=>{});
}
