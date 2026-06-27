/* YTube 432Hz — injiceres i YouTube-siden ved hvert load */
(() => {
  'use strict';
  if (window.__q432_loaded) return;
  window.__q432_loaded = true;

  // ── Hold YouTube i gang når skærmen er låst / app i baggrund ─────────────────
  // YouTube pauser sig selv når siden bliver "skjult" (Page Visibility API).
  // Vi narrer siden til altid at tro den er synlig + blokerer pause-events.
  (function keepAlive() {
    const fake = {
      hidden: false, visibilityState: 'visible',
      webkitHidden: false, webkitVisibilityState: 'visible',
    };
    for (const k in fake) {
      try {
        Object.defineProperty(Document.prototype, k, { configurable: true, get: () => fake[k] });
      } catch (_) {}
      try {
        Object.defineProperty(document, k, { configurable: true, get: () => fake[k] });
      } catch (_) {}
    }
    // Slug de events YouTube bruger til at pause i baggrund
    const swallow = (e) => { e.stopImmediatePropagation(); };
    ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze'].forEach((ev) => {
      window.addEventListener(ev, swallow, true);
      document.addEventListener(ev, swallow, true);
    });
  })();

  // ── Auto-genoptag i baggrund / PiP ───────────────────────────────────────────
  // Android saetter window.__appBackground=true naar appen forlades (se MainActivity).
  // Hvis YouTube eller systemet forsoeger at pause mens vi er i baggrunden, starter
  // vi straks igen. I forgrunden respekterer vi brugerens egne pauser.
  window.__appBackground = window.__appBackground || false;
  (function keepPlaying() {
    let lastResume = 0;
    function attach(v) {
      if (!v || v.__q432_keep) return;
      v.__q432_keep = true;
      v.addEventListener('pause', () => {
        if (!window.__appBackground) return;
        const now = Date.now();
        if (now - lastResume < 1000) return;   // undgaa play/pause-kamp (hak)
        lastResume = now;
        setTimeout(() => { try { v.play(); } catch (_) {} }, 150);
      });
    }
    setInterval(() => { attach(document.querySelector('video')); }, 1000);
  })();

  // Kaldes fra Android: skift play/pause og returner ny tilstand ('true'=spiller)
  window.__q432_toggle = function () {
    const v = document.querySelector('video');
    if (!v) return false;
    if (v.paused) { v.play(); return true; }
    v.pause(); return false;
  };

  const RATIO = 432 / 440;
  let enabled = false;
  let ctx = null, source = null, pitch = null, curVideo = null, warming = null;

  // ── CSS (pill + felt) ───────────────────────────────────────────────────────
  const style = document.createElement('style');
  style.textContent = `
    #q432-pill {
      position:fixed; bottom:20px; right:20px; z-index:2147483647;
      display:flex; align-items:center; gap:8px; padding:8px 14px 8px 10px;
      border-radius:999px; background:rgba(10,10,10,.82); border:1px solid rgba(255,255,255,.16);
      color:#d0d8f0; font:700 13px/1 "Segoe UI",system-ui,sans-serif;
      cursor:pointer; opacity:.65; backdrop-filter:blur(8px);
      transition:opacity .25s,border-color .35s,color .35s,box-shadow .35s;
      user-select:none; letter-spacing:.3px;
    }
    #q432-pill:hover { opacity:1; }
    #q432-pill .dot {
      width:9px; height:9px; border-radius:50%; background:#5a6898;
      transition:background .35s, box-shadow .35s;
    }
    #q432-pill.on {
      opacity:.95; color:#ffd66e;
      border-color:rgba(255,210,100,.55);
      box-shadow:0 0 18px rgba(255,195,80,.3);
    }
    #q432-pill.on .dot {
      background:#ffd66e; box-shadow:0 0 8px rgba(255,195,80,.9);
    }
    #q432-field {
      position:fixed; inset:0; z-index:2147483646; pointer-events:none; opacity:0;
    }
    #q432-field.collapse {
      background:radial-gradient(circle at calc(100% - 54px) calc(100% - 34px),
        rgba(255,210,120,0) 0%, rgba(255,210,120,.25) 7%, rgba(0,0,0,0) 36%);
      animation:q432c 650ms cubic-bezier(.22,1,.36,1) forwards;
    }
    @keyframes q432c {
      0%   { opacity:0; transform:scale(2.4); filter:blur(8px); }
      30%  { opacity:.9; }
      100% { opacity:0; transform:scale(.02); filter:blur(0); }
    }
  `;
  document.head.appendChild(style);

  // ── Lyd-graf ─────────────────────────────────────────────────────────────────
  function getVideo() { return document.querySelector('video'); }

  async function warmUp() {
    if (ctx) return true;
    if (warming) return warming;
    const v = getVideo();
    if (!v) return false;
    warming = (async () => {
      // 'playback' = stoerre lyd-buffer => taaler at Android struber baggrunden uden at hakke
      ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'playback' });
      // Inline processor via Blob — ingen chrome.runtime.getURL nødvendig
      const blob = new Blob([window.__PITCH_CODE__], { type: 'application/javascript' });
      const url  = URL.createObjectURL(blob);
      await ctx.audioWorklet.addModule(url);
      URL.revokeObjectURL(url);
      buildGraph(v);
      return true;
    })();
    return warming;
  }

  function buildGraph(v) {
    curVideo = v;
    source = ctx.createMediaElementSource(v);
    pitch  = new AudioWorkletNode(ctx, 'pitch-processor', {
      numberOfInputs:1, numberOfOutputs:1, channelCount:2, outputChannelCount:[2],
    });
    try { pitch.parameters.get('ratio').value = RATIO; } catch (_) {}
    route();
  }

  function route() {
    if (!source) return;
    try { source.disconnect(); } catch (_) {}
    try { pitch  && pitch.disconnect();  } catch (_) {}
    if (enabled && pitch) {
      source.connect(pitch);
      pitch.connect(ctx.destination);
    } else {
      source.connect(ctx.destination);
    }
  }

  // Hold styr på når YouTube udskifter <video>-elementet (SPA-navigation)
  setInterval(() => {
    if (!ctx) return;
    const v = getVideo();
    if (v && v !== curVideo) {
      try { source && source.disconnect(); } catch (_) {}
      try { pitch  && pitch.disconnect();  } catch (_) {}
      try { buildGraph(v); } catch (_) {}
    }
  }, 1500);

  // ── Toggle ────────────────────────────────────────────────────────────────────
  async function setEnabled(on) {
    const ok = await warmUp();
    if (!ok) { flash('Ingen video endnu'); return; }
    if (ctx.state === 'suspended') await ctx.resume();
    enabled = on;
    route();
    try { localStorage.setItem('q432_on', on ? '1' : '0'); } catch (_) {}
    updateUI();
    if (enabled) collapseField();
  }

  function toggle() { setEnabled(!enabled); }

  // ── UI ────────────────────────────────────────────────────────────────────────
  let pill, field, txt;

  function buildUI() {
    pill = document.createElement('div');
    pill.id = 'q432-pill';
    pill.title = 'YTube 432Hz — klik for at skifte';
    pill.innerHTML = '<span class="dot"></span><span class="lbl">440 Hz</span>';
    pill.addEventListener('click', toggle);
    document.body.appendChild(pill);

    field = document.createElement('div');
    field.id = 'q432-field';
    document.body.appendChild(field);

    txt = pill.querySelector('.lbl');
    updateUI();
  }

  function updateUI() {
    if (!pill) return;
    pill.classList.toggle('on', enabled);
    if (txt) txt.textContent = enabled ? '⚛ 432 Hz' : '440 Hz';
  }

  function collapseField() {
    if (!field) return;
    field.classList.remove('collapse');
    void field.offsetWidth;
    field.classList.add('collapse');
  }

  function flash(msg) {
    if (!txt) return;
    const prev = txt.textContent;
    txt.textContent = msg;
    setTimeout(() => { txt.textContent = prev; }, 1800);
  }

  // ── Reklame-skipper ───────────────────────────────────────────────────────────
  let __adTick = 0;
  setInterval(() => {
    // I baggrund/PiP: koer kun ~hvert 7. tick (=~1,4s) saa lyden faar CPU-ro
    if (window.__appBackground && (__adTick++ % 7 !== 0)) return;
    const player = document.querySelector('.html5-video-player');
    const isAd = player && (
      player.classList.contains('ad-showing') ||
      player.classList.contains('ad-interrupting')
    );
    if (isAd) {
      // Spol reklame-video til slutningen — hurtigste metode
      const vid = document.querySelector('video');
      if (vid && vid.duration && isFinite(vid.duration)) {
        vid.currentTime = vid.duration;
      }
      // Klik skip-knap hvis tilgængelig
      const skip = document.querySelector(
        '.ytp-ad-skip-button, .ytp-skip-ad-button, ' +
        '.ytp-ad-skip-button-modern, [class*="skip-ad"]'
      );
      if (skip) skip.click();
    }
    // Fjern banner- og overlay-reklamer
    document.querySelectorAll(
      '#player-ads, .ytp-ad-overlay-container, .ytp-ad-image-overlay, ' +
      '.ytp-ad-text-overlay, ytd-banner-promo-renderer, ' +
      'ytd-ad-slot-renderer, .ytd-ad-slot-renderer'
    ).forEach(el => el.remove());
  }, 200);

  // ── Init ──────────────────────────────────────────────────────────────────────
  function init() {
    buildUI();

    // Gendan sidst brugte tilstand
    try {
      if (localStorage.getItem('q432_on') === '1') {
        const go = () => {
          setEnabled(true);
          window.removeEventListener('pointerdown', go);
          window.removeEventListener('keydown', go);
        };
        window.addEventListener('pointerdown', go, { once: true });
        window.addEventListener('keydown',    go, { once: true });
      }
    } catch (_) {}

    // Forvarm AudioContext ved første gestus
    const prime = () => { warmUp(); window.removeEventListener('pointerdown', prime); };
    window.addEventListener('pointerdown', prime, { once: true });
  }

  if (document.body) init();
  else window.addEventListener('DOMContentLoaded', init);
})();
