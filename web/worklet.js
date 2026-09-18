// AudioWorklet processor: downmix mic audio to mono 16 kHz, emit Int16 PCM.
class PCM16Recorder extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;
    this.queue = new Float32Array(0);
    this.pos = 0;
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || !input[0]) return true;
    const ch = input[0];

    const qlen = this.queue.length + ch.length;
    const q = new Float32Array(qlen);
    q.set(this.queue, 0);
    q.set(ch, this.queue.length);

    const count = Math.floor((qlen - this.pos) / this.ratio);
    if (count > 0) {
      const i16 = new Int16Array(count);
      let energy = 0;
      for (let i = 0; i < count; i++) {
        const src = this.pos + i * this.ratio;
        const i0 = Math.floor(src);
        const frac = src - i0;
        const a = q[i0];
        const b = i0 + 1 < qlen ? q[i0 + 1] : q[qlen - 1];
        let s = a + (b - a) * frac;
        if (s > 1) s = 1;
        else if (s < -1) s = -1;
        const v = (s * 32767) | 0;
        i16[i] = v;
        energy += s * s;
      }
      this.pos += count * this.ratio;
      const ms = (count / 16000) * 1000;
      this.port.postMessage({level: Math.sqrt(energy / count), ms}, []);
      this.port.postMessage(i16, [i16.buffer]);
    }

    const drop = Math.floor(this.pos);
    if (drop > 0) {
      this.queue = q.slice(drop);
      this.pos -= drop;
    } else {
      this.queue = q;
    }
    return true;
  }
}

registerProcessor("pcm16-recorder", PCM16Recorder);