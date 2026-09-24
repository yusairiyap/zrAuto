(function() {
  if (window.FermataFade) return;

  // Smooth volume transitions for the YouTube page's <video> element: every start of playback
  // (resume, a new video, Chromium's own resume after regaining audio focus, a host-interruption
  // recovery) fades in instead of starting at full volume, an app-requested pause/stop/skip fades
  // out before actually acting, and the last END_FADE_S of a video fade out on their own so the
  // switch to the next video never cuts off mid-sound. There is only ever one player, so a true
  // overlapping crossfade between two videos isn't possible; fade-out -> switch -> fade-in is the
  // closest equivalent and is what this gives.
  const FADE_IN_MS = 700;
  const FADE_OUT_MS = 300;
  const END_FADE_S = 1.6;
  // Videos shorter than this don't get the automatic end-of-video fade (it would eat most of
  // them), and neither do live streams (duration is Infinity).
  const MIN_END_FADE_DURATION_S = 8;
  // Never write an exact 0 to the element's own volume -- some player builds read a volume of 0 as
  // "muted" and flip their own mute state/UI to match.
  const MIN_ELEMENT_VOLUME = 0.001;
  const STEP_MS = 20;

  const cfg = {endingEvent: 0};

  function isAd() {
    return !!window.__fermataAdShowing;
  }

  function state(v) {
    let s = v.__fermataFade;
    if (!s) {
      s = {timer: 0, doneTimer: 0, level: 1, base: 1, ending: false, pausing: false,
        usedElement: false};
      v.__fermataFade = s;
    }
    return s;
  }

  // The equalizer (youtube_equalizer.js), once it has taken over an element's audio with
  // createMediaElementSource(), exposes a dedicated gain stage for us -- ramping that runs on the
  // audio thread (sample-accurate, not throttled with the page's timers) and never touches the
  // element's own volume. Everything else falls back to stepping v.volume.
  function gainParam(v) {
    try {
      const eq = window.FermataEqualizer;
      return (eq && (typeof eq.fadeParam === 'function')) ? eq.fadeParam(v) : null;
    } catch (err) {
      return null;
    }
  }

  function clearTimers(s) {
    if (s.timer) {
      clearInterval(s.timer);
      s.timer = 0;
    }
    if (s.doneTimer) {
      clearTimeout(s.doneTimer);
      s.doneTimer = 0;
    }
  }

  // Smoothstep: eases in and out, so neither end of a fade has an audible "corner".
  function ease(from, to, t) {
    const e = t * t * (3 - 2 * t);
    return from + (to - from) * e;
  }

  function applyElementLevel(v, s, level) {
    s.level = level;
    s.usedElement = level < 1;
    const vol = (level >= 1) ? s.base : Math.max(MIN_ELEMENT_VOLUME, s.base * level);
    try {
      if (Math.abs(v.volume - vol) > 0.0005) v.volume = vol;
    } catch (err) { /* out of range, ignore */ }
  }

  // Ramps this element's fade level to `to` (0..1) over `ms`, then calls `done` (if any).
  function ramp(v, to, ms, done) {
    const s = state(v);
    clearTimers(s);
    // A hidden page has its timers throttled to about once a second -- a stepped fade there would
    // just be a few coarse jumps, so go straight to the target instead.
    if (document.hidden) ms = 0;
    const p = gainParam(v);

    if (p) {
      // The equalizer took this element over while an element-volume fade had it lowered --
      // hand the element its real volume back; the gain stage owns fading from here on.
      if (s.usedElement) {
        s.usedElement = false;
        try { v.volume = s.base; } catch (err) { /* ignore */ }
      }
      const g = p.gain;
      const now = p.ctx.currentTime;
      const from = g.value;
      try {
        g.cancelScheduledValues(now);
        if ((ms > 0) && (Math.abs(from - to) > 0.001)) {
          const n = 32;
          const curve = new Float32Array(n);
          for (let i = 0; i < n; i++) curve[i] = ease(from, to, i / (n - 1));
          g.setValueCurveAtTime(curve, now, ms / 1000);
        } else {
          g.setValueAtTime(to, now);
        }
      } catch (err) {
        g.value = to;
      }
      s.level = to;
      if (done) {
        if (ms > 0) s.doneTimer = setTimeout(() => { s.doneTimer = 0; done(); }, ms);
        else done();
      }
      return;
    }

    // Capture the "real" volume (whatever the player/user set) only while we aren't holding it
    // down ourselves, so a fade never mistakes its own lowered value for the target to restore.
    if (s.level >= 1) s.base = v.volume || s.base || 1;
    const from = s.level;

    if ((ms <= 0) || (Math.abs(from - to) < 0.001)) {
      applyElementLevel(v, s, to);
      if (done) done();
      return;
    }

    const t0 = performance.now();
    s.timer = setInterval(() => {
      const t = Math.min(1, (performance.now() - t0) / ms);
      applyElementLevel(v, s, ease(from, to, t));
      if (t >= 1) {
        clearTimers(s);
        if (done) done();
      }
    }, STEP_MS);
  }

  function fadeIn(v, ms) {
    const s = state(v);
    s.pausing = false;
    ramp(v, 1, (ms == null) ? FADE_IN_MS : ms);
  }

  function silence(v) {
    ramp(v, 0, 0);
  }

  function restoreSilently(v) {
    // Only ever called while the element is paused -- nothing is audible, so jump straight back
    // to full level. Leaving the element's own volume lowered while paused would risk the player
    // persisting that lowered value as the user's volume.
    ramp(v, 1, 0);
  }

  function isAudible(v) {
    return !v.paused && !v.ended && !v.muted && !document.hidden && (state(v).level > 0.01);
  }

  function sendEvent(data) {
    try {
      if (cfg.endingEvent && window.Fermata) window.Fermata.event(cfg.endingEvent, data);
    } catch (err) { /* bridge unavailable */ }
  }

  // --- Page-wide listeners -------------------------------------------------------------------
  // Media events don't bubble, but a document-level capture listener still sees them for every
  // <video> -- including one YouTube's SPA swaps in mid-session, before the app's own per-element
  // listeners (attached on a 1s poll) have found it.

  function onPlay(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    const s = state(v);
    s.ending = false;
    s.pausing = false;
    // Hold the level down from the moment play is requested, so the first samples the pipeline
    // produces are already quiet; 'playing' (below) ramps it back up once audio is really flowing.
    if (!isAd()) silence(v);
    else ramp(v, 1, 0);
  }

  function onPlaying(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    const s = state(v);
    // A new video YouTube loaded into the same element may skip straight to 'playing' without a
    // fresh 'play' -- don't let the previous video's end fade keep it silent.
    if (s.ending && (remaining(v) > END_FADE_S)) s.ending = false;
    if (s.ending || s.pausing) return;
    fadeIn(v, isAd() ? 0 : FADE_IN_MS);
  }

  function onLoadStart(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    state(v).ending = false;
  }

  function onPause(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    const s = state(v);
    // Paused inside the ending window without actually reaching the end (a natural end fires
    // 'pause' too, but with v.ended already true) -- let the app take the video cover it put up
    // for the upcoming switch back down, rather than leaving a paused video under black.
    if (s.ending && !v.ended) sendEvent('-1');
    s.ending = false;
    if (s.pausing) return; // our own fade-then-pause; it restores the level itself
    restoreSilently(v);
  }

  function remaining(v) {
    const d = v.duration;
    if (!isFinite(d) || (d < MIN_END_FADE_DURATION_S)) return Infinity;
    return d - v.currentTime;
  }

  function onTimeUpdate(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO') || v.paused || isAd()) return;
    const s = state(v);
    if (s.ending || s.pausing) return;
    const rem = remaining(v);
    if ((rem > END_FADE_S) || (rem <= 0)) return;
    s.ending = true;
    const ms = Math.round(rem * 1000);
    ramp(v, 0, ms);
    sendEvent(String(ms));
  }

  function onSeeking(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    const s = state(v);
    if (!s.ending || (remaining(v) <= END_FADE_S)) return;
    // Seeked back out of the ending window -- undo the end fade (and tell the app, so it can take
    // down the matching video cover) instead of leaving the rest of the video silent.
    s.ending = false;
    sendEvent('-1');
    if (!v.paused) fadeIn(v);
  }

  document.addEventListener('play', onPlay, true);
  document.addEventListener('playing', onPlaying, true);
  document.addEventListener('pause', onPause, true);
  document.addEventListener('timeupdate', onTimeUpdate, true);
  document.addEventListener('seeking', onSeeking, true);
  document.addEventListener('loadstart', onLoadStart, true);

  window.FermataFade = {
    configure(c) {
      Object.assign(cfg, c || {});
    },

    // Fades out and then pauses (and rewinds to the start if `stop`). Returns immediately; the
    // element's own 'pause' event is what tells the app it actually paused, same as without a fade.
    pause(v, stop, ms) {
      if (!v) return;
      const s = state(v);
      const finish = () => {
        try {
          if (stop) v.currentTime = 0;
          v.pause();
        } catch (err) { /* element gone */ }
        restoreSilently(v);
        s.pausing = false;
      };
      if (!isAudible(v)) {
        s.pausing = true;
        finish();
        return;
      }
      s.pausing = true;
      ramp(v, 0, (ms == null) ? FADE_OUT_MS : ms, finish);
    },

    // Fades out without pausing -- used right before switching to another video. Returns whether a
    // real (audible) fade was started, so the caller knows whether waiting for it is worth it.
    fadeOut(v, ms) {
      if (!v || !isAudible(v)) return false;
      ramp(v, 0, (ms == null) ? FADE_OUT_MS : ms);
      return true;
    },

    // Called right before an explicit play(). A play request that lands while a fade-then-pause is
    // still running cancels that pause and fades straight back in (the element never actually
    // stopped, so no 'play'/'playing' event would follow to do it). Otherwise, for a paused
    // element, starts it quiet even if the 'play' capture listener above somehow doesn't run first.
    prepareToPlay(v) {
      if (!v) return;
      const s = state(v);
      if (s.pausing && !v.paused) {
        clearTimers(s);
        s.pausing = false;
        fadeIn(v);
        return;
      }
      if (v.paused && !isAd()) silence(v);
    },

    fadeIn
  };
})();
