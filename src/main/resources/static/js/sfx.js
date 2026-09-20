/**
 * レトロ8bit風の効果音を、外部ファイルを使わずWeb Audio APIでその場で合成するモジュール。
 * ブラウザの自動再生制限に対応するため、ユーザー操作（クリック等）のタイミングで
 * unlock() を呼んでAudioContextを起こしてから鳴らす。
 */
const Sfx = (function () {
  let ctx = null;
  let muted = false;
  try {
    muted = localStorage.getItem('sfxMuted') === '1';
  } catch (e) {
    // localStorageが使えない環境（プライベートモード等）ではミュートしない
  }

  function ensureCtx() {
    if (!ctx) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return null;
      ctx = new AudioCtx();
    }
    if (ctx.state === 'suspended') {
      ctx.resume().catch(() => {});
    }
    return ctx;
  }

  function unlock() {
    try { ensureCtx(); } catch (e) { /* ignore */ }
  }

  function setMuted(value) {
    muted = value;
    try { localStorage.setItem('sfxMuted', value ? '1' : '0'); } catch (e) { /* ignore */ }
  }

  function isMuted() {
    return muted;
  }

  function tone(freq, duration, opts) {
    if (muted) return;
    const c = ensureCtx();
    if (!c) return;
    opts = opts || {};
    const t0 = c.currentTime + (opts.delay || 0);
    const osc = c.createOscillator();
    osc.type = opts.type || 'square';
    osc.frequency.setValueAtTime(freq, t0);
    if (opts.sweepTo) {
      osc.frequency.exponentialRampToValueAtTime(Math.max(1, opts.sweepTo), t0 + duration);
    }
    const gain = c.createGain();
    const vol = opts.volume !== undefined ? opts.volume : 0.15;
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(vol, t0 + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    osc.connect(gain).connect(c.destination);
    osc.start(t0);
    osc.stop(t0 + duration + 0.02);
  }

  function noiseBurst(duration, opts) {
    if (muted) return;
    const c = ensureCtx();
    if (!c) return;
    opts = opts || {};
    const t0 = c.currentTime + (opts.delay || 0);
    const bufferSize = Math.max(1, Math.floor(c.sampleRate * duration));
    const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) {
      data[i] = Math.random() * 2 - 1;
    }
    const noise = c.createBufferSource();
    noise.buffer = buffer;
    const filter = c.createBiquadFilter();
    filter.type = opts.filterType || 'bandpass';
    filter.frequency.setValueAtTime(opts.filterFreq || 1200, t0);
    if (opts.filterSweepTo) {
      filter.frequency.exponentialRampToValueAtTime(opts.filterSweepTo, t0 + duration);
    }
    const gain = c.createGain();
    const vol = opts.volume !== undefined ? opts.volume : 0.2;
    gain.gain.setValueAtTime(vol, t0);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    noise.connect(filter).connect(gain).connect(c.destination);
    noise.start(t0);
    noise.stop(t0 + duration + 0.02);
  }

  function place() {
    tone(880, 0.06, { type: 'square', volume: 0.12 });
  }

  function remove() {
    noiseBurst(0.22, { filterType: 'lowpass', filterFreq: 3000, filterSweepTo: 200, volume: 0.22 });
    tone(600, 0.18, { type: 'sawtooth', sweepTo: 110, volume: 0.10 });
  }

  function backAttack() {
    [660, 880, 1320].forEach((f, i) => tone(f, 0.08, { type: 'square', volume: 0.12, delay: i * 0.06 }));
  }

  function damage(amount) {
    const vol = Math.min(0.35, 0.15 + (amount || 1) * 0.03);
    noiseBurst(0.15, { filterType: 'lowpass', filterFreq: 900, volume: vol });
    tone(90, 0.15, { type: 'square', volume: 0.18 });
  }

  function pendingCreated() {
    tone(440, 0.07, { type: 'triangle', volume: 0.08 });
    tone(440, 0.07, { type: 'triangle', volume: 0.08, delay: 0.12 });
  }

  function victory() {
    [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.15, { type: 'square', volume: 0.14, delay: i * 0.13 }));
  }

  function defeat() {
    [392, 330, 262].forEach((f, i) => tone(f, 0.22, { type: 'square', volume: 0.14, delay: i * 0.18 }));
  }

  function draw() {
    [440, 440].forEach((f, i) => tone(f, 0.18, { type: 'triangle', volume: 0.12, delay: i * 0.22 }));
  }

  function click() {
    tone(700, 0.04, { type: 'square', volume: 0.08 });
  }

  return { unlock, setMuted, isMuted, place, remove, backAttack, damage, pendingCreated, victory, defeat, draw, click };
})();
