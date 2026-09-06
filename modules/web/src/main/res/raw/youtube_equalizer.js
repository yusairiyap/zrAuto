(function() {
  if (window.FermataEqualizer) return;

  const BAND_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  const BASS_FREQ = 200;
  const VIRT_MAX_DELAY = 0.03;
  // Bounds for the Live Hall decay length, mirroring YoutubeAddon's YT_REVERB_DURATION range
  // (300-3000ms) -- clamped here too since this script also runs standalone against whatever
  // config the page last pushed.
  const REVERB_DURATION_MIN = 0.3;
  const REVERB_DURATION_MAX = 3.0;
  const REVERB_DURATION_DEFAULT = 2.5;
  // Time constant for setTargetAtTime ramps on gains/delay times -- short enough that a fader
  // drag or a Hall Size change still feels immediate, long enough to erase the click/zipper
  // noise a hard .value= assignment causes mid-stream.
  const RAMP_TIME = 0.02;

  const state = {
    config: {
      eqEnabled: false,
      bands: BAND_FREQS.map(() => 0),
      bassEnabled: false,
      bassGain: 0,
      virtEnabled: false,
      virtStrength: 0,
      reverbEnabled: false,
      reverbStrength: 0,
      reverbDuration: REVERB_DURATION_DEFAULT,
      // 'smooth': cheap algorithmic (comb+allpass) reverb, on by default -- see buildSmoothReverb().
      // 'convolution': the original impulse-response convolver, kept for anyone who prefers its
      // character and can spare the CPU. See YoutubeEqualizerView's "Hall quality" row.
      reverbEngine: 'smooth'
    },
    ctx: null,
    chains: new WeakMap(),
    impulse: null,
    impulseDuration: null,
    impulseBuildToken: 0,
    observer: null,
    scanScheduled: false
  };

  function getContext() {
    if (!state.ctx) {
      const Ctor = window.AudioContext || window.webkitAudioContext;
      // 'playback' asks the platform for the largest output buffer it's willing to give (vs.
      // the default 'interactive', which asks for the smallest, lowest-latency one) -- this app
      // has no need for tight input-to-output latency, and 'interactive''s tiny buffer is the
      // single biggest reason a phone that's simultaneously decoding video and encoding the
      // Android Auto display projection runs out of headroom and glitches the audio callback.
      try {
        state.ctx = new Ctor({latencyHint: 'playback'});
      } catch (err) {
        state.ctx = new Ctor();
      }
    }
    if (state.ctx.state === 'suspended') state.ctx.resume().catch(() => {});
    return state.ctx;
  }

  // AudioParam has no back-reference to its AudioContext (unlike AudioNode) -- the context has
  // to be passed in explicitly to get at currentTime.
  function rampValue(ctx, param, value) {
    // setTargetAtTime glides to the target over RAMP_TIME instead of stepping there instantly --
    // a plain ".value = x" mid-stream is audible as a click/zipper on every fader drag.
    param.setTargetAtTime(value, ctx.currentTime, RAMP_TIME);
  }

  // ------------------------------------------------------------------------------------------
  // "Live Hall" reverb -- two switchable engines behind the same parallel-send interface
  // ({input, output}, wired into reverbDry/reverbWet the same way regardless of which is active).
  // ------------------------------------------------------------------------------------------

  // Schroeder/Moorer-style comb + allpass network (the classic Freeverb topology), built entirely
  // from native Web Audio nodes -- a full stereo instance is two independent mono networks (one
  // per channel, via a splitter/merger, same pattern the Virtualizer block below uses) with the
  // right channel's comb delays offset by STEREO_SPREAD_MS, matching Freeverb's own stereo
  // widening technique; without that offset, both channels would ring at identical resonant
  // frequencies and the reverb would sound narrow/mono no matter how it's mixed. Per-sample cost
  // is a handful of multiply-adds per comb/allpass, versus a windowed convolution over a multi-
  // second buffer -- roughly one to two orders of magnitude cheaper even with a full 8-comb/
  // 4-allpass network per channel, and "Hall Size" becomes an instant feedback-gain change instead
  // of a buffer rebuild.
  //
  // Tunings below are Freeverb's own classic constants (Jezar's reference comb/allpass delays and
  // stereo spread, originally specified in samples at 44.1kHz), converted to milliseconds so they
  // reproduce the same timing regardless of this AudioContext's actual sample rate.
  const COMB_DELAYS_MS = [25.31, 26.94, 28.96, 30.75, 32.24, 33.81, 35.31, 36.67];
  const STEREO_SPREAD_MS = 0.52;
  const ALLPASS_DELAYS_MS = [12.61, 10.0, 7.73, 5.10];
  const COMB_DAMPING_HZ = 3000; // fixed high-frequency damping in the feedback path -- not user-
                                 // exposed; only Hall Size (decay length) and Strength are.
  const ALLPASS_G = 0.5;

  function createComb(ctx, delaySeconds) {
    const input = ctx.createGain();
    const delay = ctx.createDelay(1);
    delay.delayTime.value = delaySeconds;
    const damp = ctx.createBiquadFilter();
    damp.type = 'lowpass';
    damp.Q.value = 0.0001;
    damp.frequency.value = COMB_DAMPING_HZ;
    const feedback = ctx.createGain();
    feedback.gain.value = 0; // set for real by setSmoothDuration()

    input.connect(delay);
    delay.connect(damp);
    damp.connect(feedback);
    feedback.connect(delay);

    return {input, output: delay, delaySeconds, feedback, nodes: [input, delay, damp, feedback]};
  }

  // One-multiply Schroeder allpass: w[n] = x[n] + g*w[n-D], y[n] = -g*x[n] + w[n-D]. `sum`
  // (=w) is the node actually fed into the delay line; `input` stays a clean, undelayed tap of
  // x[n] for the -g*x[n] feedforward term so it isn't accidentally scaled by the feedback that's
  // already folded into `sum`.
  function createAllpass(ctx, delaySeconds, g) {
    const input = ctx.createGain();
    const sum = ctx.createGain();
    const delay = ctx.createDelay(1);
    delay.delayTime.value = delaySeconds;
    const feedbackGain = ctx.createGain();
    feedbackGain.gain.value = g;
    const feedforwardGain = ctx.createGain();
    feedforwardGain.gain.value = -g;
    const output = ctx.createGain();

    input.connect(sum);
    sum.connect(delay);
    delay.connect(feedbackGain);
    feedbackGain.connect(sum);
    input.connect(feedforwardGain);
    feedforwardGain.connect(output);
    delay.connect(output);

    return {input, output, nodes: [input, sum, delay, feedbackGain, feedforwardGain, output]};
  }

  // One channel's worth of the network: `combDelaysMs` combs in parallel (summed, then scaled by
  // 1/sqrt(N) so perceived loudness doesn't keep climbing as more combs are added -- N decorrelated
  // resonances summed add roughly in power, not linearly), feeding a series allpass diffuser.
  function buildMonoReverbNetwork(ctx, combDelaysMs) {
    const input = ctx.createGain();
    const combSum = ctx.createGain();
    const combGain = ctx.createGain();
    combGain.gain.value = 1 / Math.sqrt(combDelaysMs.length);
    const nodes = [input, combSum, combGain];

    const combs = combDelaysMs.map((ms) => {
      const c = createComb(ctx, ms / 1000);
      input.connect(c.input);
      c.output.connect(combSum);
      nodes.push(...c.nodes);
      return c;
    });
    combSum.connect(combGain);

    let tail = combGain;
    for (const ms of ALLPASS_DELAYS_MS) {
      const ap = createAllpass(ctx, ms / 1000, ALLPASS_G);
      tail.connect(ap.input);
      tail = ap.output;
      nodes.push(...ap.nodes);
    }

    return {input, output: tail, combs, nodes};
  }

  function buildSmoothReverb(ctx) {
    const input = ctx.createGain();
    const splitter = ctx.createChannelSplitter(2);
    const merger = ctx.createChannelMerger(2);
    input.connect(splitter);

    const left = buildMonoReverbNetwork(ctx, COMB_DELAYS_MS);
    const right = buildMonoReverbNetwork(ctx, COMB_DELAYS_MS.map((ms) => ms + STEREO_SPREAD_MS));
    splitter.connect(left.input, 0);
    splitter.connect(right.input, 1);
    left.output.connect(merger, 0, 0);
    right.output.connect(merger, 0, 1);

    const combs = [...left.combs, ...right.combs];
    const nodes = [input, splitter, merger, ...left.nodes, ...right.nodes];

    return {input, output: merger, combs, nodes,
            setDuration: (durationSec) => setSmoothDuration(ctx, combs, durationSec)};
  }

  // Hall Size maps to RT60 (time to decay 60dB), via the standard Freeverb feedback formula
  // feedback = 0.001^(combDelay/RT60) -- longer RT60 (bigger hall) means slower-decaying feedback.
  // Applies uniformly to every comb in both channels (their differing base delaySeconds is already
  // baked into each comb object, so this still reproduces the same RT60 target per channel).
  // Genuinely free to update on every call: just a target gain ramp per comb, no buffer to rebuild.
  function setSmoothDuration(ctx, combs, rt60Seconds) {
    for (const c of combs) {
      const feedback = Math.pow(0.001, c.delaySeconds / Math.max(0.05, rt60Seconds));
      rampValue(ctx, c.feedback.gain, Math.min(0.98, feedback));
    }
  }

  // The original impulse-response convolution engine, kept as the "Rich" quality option --
  // unchanged from before this engine became switchable: normalize=true is ConvolverNode's own
  // loudness compensation for the impulse buffer's actual energy, which (unlike a fixed gain)
  // keeps perceived loudness and Hall Size's feel consistent as the buffer's length/energy changes.
  function buildConvolutionReverb(ctx) {
    const convolver = ctx.createConvolver();
    convolver.normalize = true;
    return {input: convolver, output: convolver, convolver};
  }

  // Builds the impulse response in chunks across successive tasks instead of one synchronous
  // pass -- at up to ~240,000 samples/channel (3s @ 96kHz... in practice 3s @ typical 44.1/48kHz
  // is ~144,000), generating it all in one go is a multi-hundred-millisecond main-thread stall,
  // and a guaranteed audible dropout, every time Hall Size is committed. Chunking keeps each
  // individual task well under a frame's worth of work.
  const IMPULSE_CHUNK_SAMPLES = 24000;

  function buildImpulseResponseAsync(ctx, duration, token, onDone) {
    const decay = 3;
    const length = Math.floor(ctx.sampleRate * duration);
    const impulse = ctx.createBuffer(2, length, ctx.sampleRate);
    let pos = 0;

    function step() {
      if (token !== state.impulseBuildToken) return; // superseded by a newer request -- drop it
      const end = Math.min(length, pos + IMPULSE_CHUNK_SAMPLES);
      for (let ch = 0; ch < 2; ch++) {
        const data = impulse.getChannelData(ch);
        for (let i = pos; i < end; i++) {
          data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, decay);
        }
      }
      pos = end;
      if (pos < length) setTimeout(step, 0);
      else onDone(impulse);
    }

    step();
  }

  // Requests the impulse for `duration`, sharing one build across every chain currently using the
  // convolution engine (mirrors the old single-buffer cache) -- applies it to all of them once
  // ready, and silently drops the result if a newer duration was requested meanwhile.
  function requestImpulse(duration) {
    if (state.impulseDuration === duration) return;
    const token = ++state.impulseBuildToken;
    const ctx = getContext();

    buildImpulseResponseAsync(ctx, duration, token, (impulse) => {
      if (token !== state.impulseBuildToken) return;
      state.impulse = impulse;
      state.impulseDuration = duration;

      document.querySelectorAll('video').forEach((v) => {
        const chain = state.chains.get(v);
        if (chain && chain.convolutionReverb && (chain.reverbEngineName === 'convolution')) {
          chain.convolutionReverb.convolver.buffer = impulse;
        }
      });
    });
  }

  function getReverbEngine(chain, name) {
    if (name === 'convolution') {
      if (!chain.convolutionReverb) chain.convolutionReverb = buildConvolutionReverb(chain.ctx);
      return chain.convolutionReverb;
    }
    if (!chain.smoothReverb) chain.smoothReverb = buildSmoothReverb(chain.ctx);
    return chain.smoothReverb;
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
    // Internal series wiring within the EQ block is fixed forever; only the block's entry (into
    // bands[0]) and exit (out of the last band) get spliced in/out of the active signal path by
    // rewireSpine() below.
    for (let i = 0; i < bands.length - 1; i++) bands[i].connect(bands[i + 1]);

    const bass = ctx.createBiquadFilter();
    bass.type = 'lowshelf';
    bass.frequency.value = BASS_FREQ;
    bass.gain.value = 0;

    // Virtualizer: a Haas-effect stereo widener. The right channel is fed
    // through a short delay and blended back with the dry signal on both
    // channels; strength controls both the delay time and how much of the
    // delayed signal is mixed in, so it degrades gracefully to a plain
    // pass-through at strength 0 instead of a hard bypass switch. Internal wiring is fixed
    // forever; only the block's entry (into splitter) and exit (out of merger) get spliced in/out.
    const splitter = ctx.createChannelSplitter(2);
    const merger = ctx.createChannelMerger(2);
    const dryR = ctx.createGain();
    const delay = ctx.createDelay(VIRT_MAX_DELAY);
    const wetR = ctx.createGain();
    const wetL = ctx.createGain();

    splitter.connect(merger, 0, 0);
    splitter.connect(dryR, 1);
    dryR.connect(merger, 0, 1);
    splitter.connect(delay, 1);
    delay.connect(wetR);
    wetR.connect(merger, 0, 1);
    delay.connect(wetL);
    wetL.connect(merger, 0, 0);

    // Live Hall reverb: a parallel send -- the dry signal always passes through, the reverb
    // engine's "wet" signal layers on top, rather than replacing the direct sound (matching how a
    // real hall effect is used). The engine itself (smooth or convolution) is created lazily by
    // getReverbEngine() the first time it's actually selected -- not here, and not both up front --
    // so picking one quality never pays for building the other.
    const reverbDry = ctx.createGain();
    const reverbWet = ctx.createGain();

    // Safety limiter: catches the combined output of the whole chain so pushing several controls
    // toward their maxima can't hard-clip -- Web Audio applies no headroom protection at the
    // destination by default.
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -1;
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.003;
    limiter.release.value = 0.25;
    reverbDry.connect(limiter);
    reverbWet.connect(limiter);
    limiter.connect(ctx.destination);

    // None of the EQ/bass/virtualizer/reverb stages are connected to `source` (or each other) yet
    // -- every processing node here has a real, non-zero per-sample CPU cost once connected (the
    // reverb engines especially so), so a disabled effect should cost nothing, not just produce
    // silent output. rewireSpine()/applyToChain() below connect only the currently-enabled stages
    // into the active signal path, and only reconnect when the enabled set actually changes.
    return {video, ctx, source, bands, bass, splitter, merger, dryR, delay, wetR, wetL,
            reverbDry, reverbWet, limiter, smoothReverb: null, convolutionReverb: null,
            reverbEngineName: null, reverbConnected: false, reverbDuration: null,
            spineKey: null, tail: null};
  }

  // Reconnects the "spine" (source -> [EQ bands] -> [bass] -> [virtualizer] -> reverbDry/engine)
  // to include only the currently-enabled stages. Only rewires when the enabled set actually
  // changed (a real on/off toggle), not on every applyToChain() call -- e.g. dragging an EQ slider
  // pushes new gain values on every frame, and rewiring the graph on each one would risk an
  // audible micro-glitch for no reason.
  function rewireSpine(chain, cfg) {
    const key = (cfg.eqEnabled ? 'E' : '') + (cfg.bassEnabled ? 'B' : '') + (cfg.virtEnabled ? 'V' : '');
    if (chain.spineKey === key) return;

    if (chain.tail) {
      try { chain.tail.disconnect(chain.reverbDry); } catch (err) { /* already disconnected */ }
      if (chain.reverbConnected) {
        const engine = getReverbEngine(chain, chain.reverbEngineName);
        try { chain.tail.disconnect(engine.input); } catch (err) { /* already disconnected */ }
      }
    }
    try { chain.source.disconnect(); } catch (err) { /* already disconnected */ }
    try { chain.bands[chain.bands.length - 1].disconnect(); } catch (err) { /* already disconnected */ }
    try { chain.bass.disconnect(); } catch (err) { /* already disconnected */ }
    try { chain.merger.disconnect(); } catch (err) { /* already disconnected */ }

    let tail = chain.source;
    if (cfg.eqEnabled) {
      tail.connect(chain.bands[0]);
      tail = chain.bands[chain.bands.length - 1];
    }
    if (cfg.bassEnabled) {
      tail.connect(chain.bass);
      tail = chain.bass;
    }
    if (cfg.virtEnabled) {
      tail.connect(chain.splitter);
      tail = chain.merger;
    }

    tail.connect(chain.reverbDry);
    if (chain.reverbConnected) {
      const engine = getReverbEngine(chain, chain.reverbEngineName);
      tail.connect(engine.input);
    }

    chain.tail = tail;
    chain.spineKey = key;
  }

  // Disconnects whichever reverb engine is currently spliced into the chain (tail -> engine.input,
  // engine.output -> reverbWet) -- used both when Live Hall is turned off and when switching
  // quality (smooth <-> convolution) out from under an already-connected chain.
  function disconnectActiveReverb(chain) {
    if (!chain.reverbConnected) return;
    const engine = getReverbEngine(chain, chain.reverbEngineName);
    try { chain.tail.disconnect(engine.input); } catch (err) { /* already disconnected */ }
    try { engine.output.disconnect(chain.reverbWet); } catch (err) { /* already disconnected */ }
    chain.reverbConnected = false;
    chain.reverbEngineName = null;
  }

  function connectActiveReverb(chain, name) {
    const engine = getReverbEngine(chain, name);
    chain.tail.connect(engine.input);
    engine.output.connect(chain.reverbWet);
    chain.reverbConnected = true;
    chain.reverbEngineName = name;
  }

  function applyToChain(chain) {
    const cfg = state.config;
    rewireSpine(chain, cfg);

    const ctx = chain.ctx;

    for (let i = 0; i < chain.bands.length; i++) {
      rampValue(ctx, chain.bands[i].gain, cfg.eqEnabled ? (cfg.bands[i] || 0) : 0);
    }

    rampValue(ctx, chain.bass.gain, cfg.bassEnabled ? cfg.bassGain : 0);

    const strength = cfg.virtEnabled ? Math.max(0, Math.min(1, cfg.virtStrength)) : 0;
    rampValue(ctx, chain.delay.delayTime, 0.005 + VIRT_MAX_DELAY * 0.67 * strength);
    rampValue(ctx, chain.dryR.gain, 1 - 0.5 * strength);
    rampValue(ctx, chain.wetR.gain, 0.5 * strength);
    rampValue(ctx, chain.wetL.gain, 0.3 * strength);

    const engineName = (cfg.reverbEngine === 'convolution') ? 'convolution' : 'smooth';

    if (!cfg.reverbEnabled) {
      disconnectActiveReverb(chain);
    } else if (!chain.reverbConnected || (chain.reverbEngineName !== engineName)) {
      disconnectActiveReverb(chain);
      connectActiveReverb(chain, engineName);
      // Force the duration section below to (re)apply to whichever engine is now active -- it's
      // otherwise guarded on "did the requested duration actually change", which would silently
      // skip building/assigning the convolution engine's impulse buffer when switching quality
      // without also changing Hall Size.
      chain.reverbDuration = null;
    }

    // Live Hall's slider allows up to 150% (see YoutubeEqualizerView's Live Hall channel), unlike
    // Bass/Virtualizer which stay 0-100% -- keep this ceiling in sync with that slider's max, and
    // note it deliberately differs from the native PresetReverb path, whose aux send level is a
    // hard 0.0-1.0 platform API contract with no headroom above unity to raise a ceiling into.
    const REVERB_MAX_STRENGTH = 1.5;
    const reverbStrength = cfg.reverbEnabled ?
        Math.max(0, Math.min(REVERB_MAX_STRENGTH, cfg.reverbStrength)) : 0;
    rampValue(ctx, chain.reverbDry.gain, 1);
    rampValue(ctx, chain.reverbWet.gain, reverbStrength * 0.6);

    if (!cfg.reverbEnabled) return;

    const reverbDuration = Math.max(REVERB_DURATION_MIN,
        Math.min(REVERB_DURATION_MAX, cfg.reverbDuration || REVERB_DURATION_DEFAULT));

    if (engineName === 'smooth') {
      // Genuinely free to update on every call -- just a target-gain ramp per comb, no buffer to
      // rebuild -- so no need to guard this behind a "did it actually change" check the way the
      // convolution engine below does.
      chain.smoothReverb.setDuration(reverbDuration);
      chain.reverbDuration = reverbDuration;
    } else if (chain.reverbDuration !== reverbDuration) {
      // Real CPU work (rebuilding a multi-second noise buffer), so only touch it when the
      // requested duration actually changed -- and do it off the main thread's critical path via
      // requestImpulse()'s chunked build, applied to every convolution chain once ready.
      chain.reverbDuration = reverbDuration;
      requestImpulse(reverbDuration);
      if (state.impulseDuration === reverbDuration) chain.convolutionReverb.convolver.buffer = state.impulse;
    }
  }

  function anyEffectEnabled(cfg) {
    return cfg.eqEnabled || cfg.bassEnabled || cfg.virtEnabled || cfg.reverbEnabled;
  }

  function attach(video) {
    if (video.getAttribute('FermataEqAttached') === 'true') return;
    // createMediaElementSource() irreversibly reroutes this element's audio through Web Audio --
    // there's no going back to the browser's zero-overhead direct path for it. So don't build the
    // graph (no AudioContext, no reverb engine, etc.) until the user has actually enabled
    // something; configure() re-scans on every config push, so the very next one after enabling
    // naturally retries any previously-skipped video.
    if (!anyEffectEnabled(state.config)) return;
    video.setAttribute('FermataEqAttached', 'true');

    let chain;
    try {
      chain = buildChain(video);
    } catch (err) {
      console.debug('FermataEqualizer: failed to attach', err);
      video.removeAttribute('FermataEqAttached');
      return;
    }

    state.chains.set(video, chain);
    applyToChain(chain);
    video.addEventListener('playing', () => getContext());
  }

  function disconnectChain(chain) {
    const nodes = [chain.source, ...chain.bands, chain.bass, chain.splitter, chain.merger,
                    chain.dryR, chain.delay, chain.wetR, chain.wetL, chain.reverbDry,
                    chain.reverbWet, chain.limiter];
    // Include every internal node of whichever reverb engine(s) were actually built for this
    // chain, not just their outer input/output boundary -- a comb/allpass's internal feedback
    // loop is otherwise left fully interconnected with itself, and while modern Web Audio
    // implementations are specified to still collect a cycle with no path to the destination,
    // that's not something to lean on across every WebView version this app runs on.
    if (chain.smoothReverb) nodes.push(...chain.smoothReverb.nodes);
    if (chain.convolutionReverb) nodes.push(chain.convolutionReverb.convolver);
    for (const n of nodes) {
      try { n.disconnect(); } catch (err) { /* already disconnected */ }
    }
  }

  function detach(video) {
    const chain = state.chains.get(video);
    if (!chain) return;
    disconnectChain(chain);
    state.chains.delete(video);
    // Without this, a video element YouTube's SPA player reuses later (it does, on some
    // transitions) would find its old 'true' marker still set and attach() would silently skip
    // rebuilding a chain for it forever.
    video.removeAttribute('FermataEqAttached');
  }

  // Shared by both directions below: finds every <video> that `node` either is or contains.
  function collectVideos(node, out) {
    if (node.nodeType !== 1) return;
    if (node.tagName === 'VIDEO') out.push(node);
    else if (node.querySelectorAll) node.querySelectorAll('video').forEach((v) => out.push(v));
  }

  function scan() {
    document.querySelectorAll('video').forEach(attach);
  }

  function scheduleScan() {
    if (state.scanScheduled) return;
    state.scanScheduled = true;
    // Coalesces every mutation batch in the same frame into a single scan -- YouTube's SPA
    // mutates the DOM (progress bar, chips, thumbnails) dozens to hundreds of times a second, and
    // running querySelectorAll('video') over the whole document on every single one of those was
    // real, measurable main-thread cost competing with the audio callback for CPU, independent of
    // which effects were even enabled.
    const raf = window.requestAnimationFrame || ((cb) => setTimeout(cb, 16));
    raf(() => {
      state.scanScheduled = false;
      scan();
    });
  }

  // Handles both discovering newly-added <video> elements and tearing down chains for ones
  // YouTube's SPA player removes -- e.g. on a video-to-video transition, which swaps in a fresh
  // element -- so a stale chain (with its own live reverb) never keeps running alongside the new
  // one. Only actually schedules a scan when a mutation batch plausibly touched a <video> --
  // skips the (vast majority of) batches that are just YouTube's UI chrome updating.
  function handleMutations(mutations) {
    let touchedVideo = false;

    for (const m of mutations) {
      m.removedNodes.forEach((n) => {
        const removed = [];
        collectVideos(n, removed);
        removed.forEach(detach);
        if (removed.length > 0) touchedVideo = true;
      });

      if (!touchedVideo) {
        m.addedNodes.forEach((n) => {
          const added = [];
          collectVideos(n, added);
          if (added.length > 0) touchedVideo = true;
        });
      }
    }

    if (touchedVideo) scheduleScan();
  }

  function startWatching() {
    scan();
    if (!state.observer) {
      state.observer = new MutationObserver(handleMutations);
      state.observer.observe(document.body, {childList: true, subtree: true});
    }
  }

  // Only worth running the observer (and paying for its own, smaller share of main-thread cost)
  // while at least one effect is actually enabled -- previously this ran for the entire session
  // as soon as configure() was ever called once, even with every effect off.
  function stopWatchingIfIdle() {
    if (state.observer && !anyEffectEnabled(state.config)) {
      state.observer.disconnect();
      state.observer = null;
    }
  }

  window.FermataEqualizer = {
    configure(config) {
      state.config = Object.assign({}, state.config, config);
      if (Array.isArray(config.bands)) state.config.bands = config.bands;

      if (anyEffectEnabled(state.config)) {
        startWatching();
      } else {
        stopWatchingIfIdle();
      }

      document.querySelectorAll('video').forEach((v) => {
        const chain = state.chains.get(v);
        if (chain) applyToChain(chain);
      });
    }
  };
})();
