// Synthesised move sounds (no audio assets): a soft wooden tap, a heavier capture,
// a bright two-tone check and a gentle chord at game end.
let ctx = null;
export const sound = { enabled: true };

function audio() {
  if (!ctx) { const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null; ctx = new AC(); }
  if (ctx.state === 'suspended') ctx.resume();
  return ctx;
}

function tap(freq, dur, gain, type = 'triangle', delay = 0) {
  const a = audio(); if (!a) return;
  const t = a.currentTime + delay, o = a.createOscillator(), g = a.createGain(), f = a.createBiquadFilter();
  o.type = type; o.frequency.setValueAtTime(freq, t); o.frequency.exponentialRampToValueAtTime(freq * 0.55, t + dur);
  f.type = 'lowpass'; f.frequency.value = 2400;
  g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(gain, t + 0.006); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(f).connect(g).connect(a.destination); o.start(t); o.stop(t + dur + 0.02);
}

function noise(dur, gain, delay = 0) {
  const a = audio(); if (!a) return;
  const t = a.currentTime + delay, buf = a.createBuffer(1, Math.ceil(a.sampleRate * dur), a.sampleRate), d = buf.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / d.length) ** 3;
  const s = a.createBufferSource(), g = a.createGain(), f = a.createBiquadFilter();
  f.type = 'bandpass'; f.frequency.value = 1300; g.gain.value = gain;
  s.buffer = buf; s.connect(f).connect(g).connect(a.destination); s.start(t);
}

export function play(kind) {
  if (!sound.enabled) return;
  try {
    if (kind === 'move') { tap(520, 0.09, 0.35); noise(0.04, 0.25); }
    else if (kind === 'capture') { tap(300, 0.14, 0.5, 'square'); noise(0.08, 0.5); }
    else if (kind === 'check') { tap(880, 0.12, 0.22, 'sine'); tap(1320, 0.16, 0.18, 'sine', 0.08); }
    else if (kind === 'end') { [523, 659, 784].forEach((f, i) => tap(f, 0.9, 0.16, 'sine', i * 0.07)); }
    else if (kind === 'error') tap(160, 0.12, 0.2, 'sawtooth');
  } catch { /* audio is best-effort */ }
}
