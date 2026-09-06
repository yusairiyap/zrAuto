(function() {
  if (window.FermataEqualizer) return;

  const BAND_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  const BASS_FREQ = 200;
  const VIRT_MAX_DELAY = 0.03;

  const state = {
    config: {
      eqEnabled: false,
      bands: BAND_FREQS.map(() => 0),
      bassEnabled: false,
      bassGain: 0,
      virtEnabled: false,
      virtStrength: 0,
      reverbEnabled: false,
      reverbStrength: 0
    },
    ctx: null,
    chains: new WeakMap(),
    impulse: null
  };

  function getContext() {
    if (!state.ctx) {
      const Ctor = window.AudioContext || window.webkitAudioContext;
      state.ctx = new Ctor();
    }
    if (state.ctx.state === 'suspended') state.ctx.resume().catch(() => {});
    return state.ctx;
  }

  // "Live Hall" reverb: Web Audio has no built-in hall-reverb node, so this
  // synthesizes a plausible impulse response -- exponentially-decaying
  // stereo white noise, a standard procedural technique for a ConvolverNode
  // -- once per AudioContext and reuses it for every video's chain.
  function getImpulseResponse(ctx) {
    if (state.impulse) return state.impulse;

    const duration = 2.5, decay = 3;
    const length = Math.floor(ctx.sampleRate * duration);
    const impulse = ctx.createBuffer(2, length, ctx.sampleRate);

    for (let ch = 0; ch < 2; ch++) {
      const data = impulse.getChannelData(ch);
      for (let i = 0; i < length; i++) {
        data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, decay);
      }
    }

    return state.impulse = impulse;
  }

  function buildChain(video) {
    const ctx = getContext();
    const source = ctx.createMediaElementSource(video);

    const bands = BAND_FREQS.map((freq) => {
      const f = ctx.createBiquadFilter();
      f.type = 'peaking';
      f.frequency.value = freq;
      f.Q.value = 1;
      f.gain.value = 0;
      return f;
    });

    const bass = ctx.createBiquadFilter();
    bass.type = 'lowshelf';
    bass.frequency.value = BASS_FREQ;
    bass.gain.value = 0;

    let node = source;
    for (const b of bands) { node.connect(b); node = b; }
    node.connect(bass);

    // Virtualizer: a Haas-effect stereo widener. The right channel is fed
    // through a short delay and blended back with the dry signal on both
    // channels; strength controls both the delay time and how much of the
    // delayed signal is mixed in, so it degrades gracefully to a plain
    // pass-through at strength 0 instead of a hard bypass switch.
    const splitter = ctx.createChannelSplitter(2);
    const merger = ctx.createChannelMerger(2);
    const dryR = ctx.createGain();
    const delay = ctx.createDelay(VIRT_MAX_DELAY);
    const wetR = ctx.createGain();
    const wetL = ctx.createGain();

    bass.connect(splitter);
    splitter.connect(merger, 0, 0);
    splitter.connect(dryR, 1);
    dryR.connect(merger, 0, 1);
    splitter.connect(delay, 1);
    delay.connect(wetR);
    wetR.connect(merger, 0, 1);
    delay.connect(wetL);
    wetL.connect(merger, 0, 0);

    // Live Hall reverb: a parallel send -- the dry signal always passes
    // through, the convolved "wet" signal layers on top, rather than
    // replacing the direct sound (matching how a real hall effect is used).
    // The convolver itself is NOT connected here -- a connected ConvolverNode keeps running its
    // (by far the most expensive computation in this whole chain) real-time convolution on every
    // audio frame regardless of its output gain, so wiring it in unconditionally would silently
    // cost CPU even when Live Hall is off and every other effect is cheap by comparison.
    // applyToChain() connects/disconnects it based on cfg.reverbEnabled instead.
    const convolver = ctx.createConvolver();
    convolver.buffer = getImpulseResponse(ctx);
    convolver.normalize = true;
    const reverbDry = ctx.createGain();
    const reverbWet = ctx.createGain();
    merger.connect(reverbDry);

    // Safety limiter: catches the combined output of the whole chain (reverbDry always carries the
    // full dry signal, even with reverb off) so pushing several controls toward their maxima can't
    // hard-clip -- Web Audio applies no headroom protection at the destination by default.
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -1;
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.003;
    limiter.release.value = 0.25;
    reverbDry.connect(limiter);
    reverbWet.connect(limiter);
    limiter.connect(ctx.destination);

    return {video, source, bands, bass, splitter, merger, dryR, delay, wetR, wetL, convolver,
            reverbDry, reverbWet, limiter, reverbConnected: false};
  }

  function applyToChain(chain) {
    const cfg = state.config;

    for (let i = 0; i < chain.bands.length; i++) {
      chain.bands[i].gain.value = cfg.eqEnabled ? (cfg.bands[i] || 0) : 0;
    }

    chain.bass.gain.value = cfg.bassEnabled ? cfg.bassGain : 0;

    const strength = cfg.virtEnabled ? Math.max(0, Math.min(1, cfg.virtStrength)) : 0;
    chain.delay.delayTime.value = 0.005 + VIRT_MAX_DELAY * 0.67 * strength;
    chain.dryR.gain.value = 1 - 0.5 * strength;
    chain.wetR.gain.value = 0.5 * strength;
    chain.wetL.gain.value = 0.3 * strength;

    // Live Hall's slider allows up to 150% (see YoutubeEqualizerView's Live Hall channel), unlike
    // Bass/Virtualizer which stay 0-100% -- keep this ceiling in sync with that slider's max, and
    // note it deliberately differs from the native PresetReverb path, whose aux send level is a
    // hard 0.0-1.0 platform API contract with no headroom above unity to raise a ceiling into.
    const REVERB_MAX_STRENGTH = 1.5;
    const reverbStrength = cfg.reverbEnabled ?
        Math.max(0, Math.min(REVERB_MAX_STRENGTH, cfg.reverbStrength)) : 0;
    chain.reverbDry.gain.value = 1;
    chain.reverbWet.gain.value = reverbStrength * 0.6;

    if (cfg.reverbEnabled && !chain.reverbConnected) {
      chain.merger.connect(chain.convolver);
      chain.convolver.connect(chain.reverbWet);
      chain.reverbConnected = true;
    } else if (!cfg.reverbEnabled && chain.reverbConnected) {
      chain.merger.disconnect(chain.convolver);
      chain.convolver.disconnect(chain.reverbWet);
      chain.reverbConnected = false;
    }
  }

  function anyEffectEnabled(cfg) {
    return cfg.eqEnabled || cfg.bassEnabled || cfg.virtEnabled || cfg.reverbEnabled;
  }

  function attach(video) {
    if (video.getAttribute('FermataEqAttached') === 'true') return;
    // createMediaElementSource() irreversibly reroutes this element's audio through Web Audio --
    // there's no going back to the browser's zero-overhead direct path for it. So don't build the
    // graph (no AudioContext, no ConvolverNode running a continuous convolution, etc.) until the
    // user has actually enabled something; configure() re-scans on every config push, so the very
    // next one after enabling naturally retries any previously-skipped video.
    if (!anyEffectEnabled(state.config)) return;
    video.setAttribute('FermataEqAttached', 'true');

    let chain;
    try {
      chain = buildChain(video);
    } catch (err) {
      console.debug('FermataEqualizer: failed to attach', err);
      return;
    }

    state.chains.set(video, chain);
    applyToChain(chain);
    video.addEventListener('playing', () => getContext());
  }

  function disconnectChain(chain) {
    const nodes = [chain.source, ...chain.bands, chain.bass, chain.splitter, chain.merger,
                    chain.dryR, chain.delay, chain.wetR, chain.wetL, chain.convolver,
                    chain.reverbDry, chain.reverbWet, chain.limiter];
    for (const n of nodes) {
      try { n.disconnect(); } catch (err) { /* already disconnected */ }
    }
  }

  function detach(video) {
    const chain = state.chains.get(video);
    if (!chain) return;
    disconnectChain(chain);
    state.chains.delete(video);
  }

  function collectRemovedVideos(node, out) {
    if (node.nodeType !== 1) return;
    if (node.tagName === 'VIDEO') out.push(node);
    else if (node.querySelectorAll) node.querySelectorAll('video').forEach((v) => out.push(v));
  }

  function scan() {
    document.querySelectorAll('video').forEach(attach);
  }

  // Handles both discovering newly-added <video> elements (as before) and tearing down chains for
  // ones YouTube's SPA player removes -- e.g. on a video-to-video transition, which swaps in a
  // fresh element -- so a stale chain (with its own live convolution reverb) never keeps running
  // alongside the new one.
  function handleMutations(mutations) {
    for (const m of mutations) {
      m.removedNodes.forEach((n) => {
        const removed = [];
        collectRemovedVideos(n, removed);
        removed.forEach(detach);
      });
    }
    scan();
  }

  function startWatching() {
    scan();
    if (!window.__fermataEqObserver) {
      window.__fermataEqObserver = new MutationObserver(handleMutations);
      window.__fermataEqObserver.observe(document.body, {childList: true, subtree: true});
    }
  }

  window.FermataEqualizer = {
    configure(config) {
      state.config = Object.assign({}, state.config, config);
      if (Array.isArray(config.bands)) state.config.bands = config.bands;
      startWatching();

      document.querySelectorAll('video').forEach((v) => {
        const chain = state.chains.get(v);
        if (chain) applyToChain(chain);
      });
    }
  };
})();
