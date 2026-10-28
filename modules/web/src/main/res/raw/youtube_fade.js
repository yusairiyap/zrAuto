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
  // A new video fades in from START_LEVEL (never silence, which cut the start off) over START_FADE_S of
  // its own playback time, evenly in loudness (the same dB per moment, `rise`): a smoothstep from 20%
  // stayed near silence at first and sounded late, and one from 50% jumped in, then swelled; neither
  // sounded smooth.
  // time, set up at 'loadstart' when its source has played nothing yet. Holding it at zero until
  // 'playing' and fading in from there cut off the song's first moment: the sound is already flowing
  // when that event reaches the page (busiest just then, loading the video), the log showed the
  // playback time some 100 ms ahead of it. Following the playback time rather than the clock, the
  // fade can't run ahead of the sound while the video is still starting, and a late step of a busy
  // page lands where the song really is. With effects on, it runs on the audio clock (gain stage).
  const START_LEVEL = 0.25;
  const START_FADE_S = 0.5;
  const FADE_OUT_MS = 350;
  // Silence held after a fade-out before the element is actually paused, so whatever audio the
  // pipeline already had buffered drains out at zero level instead of being cut mid-waveform.
  const PAUSE_HOLD_MS = 80;
  // How long a paused element stays at zero level before its own volume is put back (element-volume
  // path only, see restoreSilently()). Restoring it the instant pause() returns let the few
  // milliseconds of audio still buffered in the pipeline play out at full volume -- the short
  // crackle/blip heard on pause.
  const RESTORE_AFTER_PAUSE_MS = 400;
  // Gap between holding a gain at its current value and starting a new curve from it -- a
  // setValueCurveAtTime() may not overlap any other automation event, including the hold itself.
  const CURVE_START_DELAY_S = 0.005;
  const END_FADE_S = 1.6;
  // Videos shorter than this don't get the automatic end-of-video fade (it would eat most of
  // them), and neither do live streams (duration is Infinity).
  const MIN_END_FADE_DURATION_S = 8;
  // Never write an exact 0 to the element's own volume -- some player builds read a volume of 0 as
  // "muted" and flip their own mute state/UI to match.
  const MIN_ELEMENT_VOLUME = 0.001;
  const STEP_MS = 10;

  // warmup: a new video plays its first WARM_S silently, then goes back to where it started and fades in from there
  // (YouTube setting "Warm up the sound before a song starts"). The stutter at a song's start was traced to the
  // sound output starting: in the first half second the song's clock stalls ~100 ms and then jumps to catch up
  // (or just jumps ahead). A seek was never heard to stutter (the output is already running then), so the hiccup
  // happens while the song is still silent. The cost: each song is heard about WARM_S later.
  const cfg = {endingEvent: 0, warmup: true, audioOnly: false};
  const WARM_S = 0.6;
  // The first song of a freshly loaded page (a YouTube song after a local one, the first after an app start) warms up longer:
  // there the sound output was still settling just after a 0.6 s warm-up (its reported latency changed between the
  // two 'playing' events and the clock jumped ~70 ms right after the jump back).
  // A switch through YouTube's router (YoutubeWebView#loadVideo, "page navigation") counts as a first start too (longWarm):
  // after a 0.6 s warm-up there the clock ran evenly for 0.4 s and then jumped ~57 ms (Oct 29 log), which a 1 s one did not.
  const WARM_FIRST_S = 1.0;
  let pageFirstStart = true;

  function trace(msg) {
    try {
      if (window.__fermataTrace) window.__fermataTrace(msg);
    } catch (err) { /* no tracer */ }
  }

  function isAd() {
    return !!window.__fermataAdShowing;
  }

  function state(v) {
    let s = v.__fermataFade;
    if (!s) {
      s = {timer: 0, doneTimer: 0, restoreTimer: 0, level: 1, base: 1, ending: false,
        pausing: false, usedElement: false, applied: null, fresh: false};
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
    s.startStep = null;
    if (s.doneTimer) {
      clearTimeout(s.doneTimer);
      s.doneTimer = 0;
    }
    if (s.restoreTimer) {
      clearTimeout(s.restoreTimer);
      s.restoreTimer = 0;
    }
  }

  // Smoothstep: eases in and out, so neither end of a fade has an audible "corner".
  function ease(from, to, t) {
    const e = t * t * (3 - 2 * t);
    return from + (to - from) * e;
  }

  // Even in loudness: equal steps in dB from `from` to `to` (both above zero).
  function rise(from, to, t) {
    if ((from < 0.01) || (to < 0.01)) return ease(from, to, t);
    return from * Math.pow(to / from, t);
  }

  function applyElementLevel(v, s, level) {
    s.level = level;
    s.usedElement = level < 1;
    const vol = (level >= 1) ? s.base : Math.max(MIN_ELEMENT_VOLUME, s.base * level);
    try {
      if (Math.abs(v.volume - vol) > 0.0005) v.volume = vol;
    } catch (err) { /* out of range, ignore */ }
    s.applied = v.volume;
  }

  // The player itself set the element's volume while this script holds it down (YouTube does as
  // each new video loads: its own volume, levelled per video). Taken as the volume to come back to,
  // and the level held is put back at once -- left alone, the next song's first moment played at
  // full volume, then dropped to silence for the fade-in: a blip and a dip, the stutter heard on
  // every Music tab skip.
  function playerSetVolume(v, s) {
    return (s.applied != null) && (v.volume > MIN_ELEMENT_VOLUME * 2) &&
      (Math.abs(v.volume - s.applied) > 0.002);
  }

  function onVolumeChange(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    const s = state(v);
    if ((s.level >= 1) || gainParam(v) || !playerSetVolume(v, s)) return;
    s.base = v.volume;
    applyElementLevel(v, s, s.level);
  }

  // Ramps this element's fade level to `to` (0..1) over `ms`, then calls `done` (if any).
  function ramp(v, to, ms, done, shape) {
    const curveOf = shape || ease;
    const s = state(v);
    clearTimers(s);
    const p = gainParam(v);
    // A hidden page may have its timers throttled to about once a second -- a stepped element fade there would
    // just be a few coarse jumps, so go straight to the target instead. The effects path's gain curve runs in the
    // audio renderer, sample-accurate whatever the timers do, so it keeps its fade.
    if (pageHidden() && !p) ms = 0;

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
        // Freeze the gain exactly where it is right now -- including partway through a previous
        // curve (e.g. pausing during a fade-in). cancelScheduledValues() alone leaves a curve
        // that already started in place, and scheduling a new one over it throws, which used to
        // fall through to the catch below: an instant jump to the target, i.e. a click.
        if (typeof g.cancelAndHoldAtTime === 'function') {
          g.cancelAndHoldAtTime(now);
        } else {
          g.cancelScheduledValues(now);
          g.setValueAtTime(from, now);
        }
        if ((ms > 0) && (Math.abs(from - to) > 0.001)) {
          const n = 64;
          const curve = new Float32Array(n);
          for (let i = 0; i < n; i++) curve[i] = curveOf(from, to, i / (n - 1));
          g.setValueCurveAtTime(curve, now + CURVE_START_DELAY_S, ms / 1000);
        } else {
          g.setValueAtTime(to, now + CURVE_START_DELAY_S);
        }
      } catch (err) {
        try {
          g.cancelScheduledValues(0);
          g.setTargetAtTime(to, now, Math.max(0.005, ms / 4000));
        } catch (err2) {
          g.value = to;
        }
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
    else if (playerSetVolume(v, s)) s.base = v.volume; // See onVolumeChange().
    const from = s.level;

    if ((ms <= 0) || (Math.abs(from - to) < 0.001)) {
      applyElementLevel(v, s, to);
      if (done) done();
      return;
    }

    // From the level held right now, not whatever the element happens to be at (the player may have
    // put its own volume back since): the first step would otherwise be a jump down.
    applyElementLevel(v, s, from);
    const t0 = performance.now();
    s.timer = setInterval(() => {
      const t = Math.min(1, (performance.now() - t0) / ms);
      applyElementLevel(v, s, curveOf(from, to, t));
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

  // The start of a new video: from START_LEVEL to full over START_FADE_S of playback (see there).
  function startFade(v) {
    const s = state(v);
    clearTimers(s);
    s.pausing = false;
    // Only while the video is not watched: a hidden page (the Music tab playing YouTube, the screen off) or music mode
    // (cfg.audioOnly, set by the app). That is where the stutter was heard (never in fullscreen video), and a watched
    // video would show its first moment playing and jumping back, which no way of hiding the picture covered reliably.
    // Its steps are also driven by 'timeupdate' (below), so a page whose timers are held back still leaves the silent part.
    const hidden = pageHidden();
    // -1: no warm-up; 0: warming up (silent); 1: done (went back to the start).
    let warm = (cfg.warmup && (hidden || cfg.audioOnly) && !isAd()) ? 0 : -1;
    let warmS = WARM_S;
    if (warm === 0) {
      if (pageFirstStart) warmS = WARM_FIRST_S;
      pageFirstStart = false;
    }
    // The effects path (the equalizer's gain stage) is held at 0 the same way; set here, not through ramp(), which would clear
    // this very timer when the equalizer takes the element over in the middle of the warm-up.
    let gainSilenced = false;
    const silenceGain = (p) => {
      try {
        p.gain.cancelScheduledValues(0);
        p.gain.setValueAtTime(0, p.ctx.currentTime);
      } catch (err) { /* context gone */ }
      if (s.usedElement) {
        s.usedElement = false;
        try { v.volume = s.base; } catch (err) { /* ignore */ }
      }
      s.level = 0;
      gainSilenced = true;
    };
    let startAt = 0;
    ramp(v, (warm === 0) ? 0 : START_LEVEL, 0);
    let t0 = -1;
    let pausedSince = 0;
    // How evenly the fade ran: a page busy starting the video delays these 10 ms ticks, and the level then
    // stays low and jumps (heard as a dip). Traced once the fade is done.
    let ticks = 0, lastTick = 0, maxGap = 0, maxJump = 0;
    const step = () => {
      // Loaded but left paused (a cued video, a reload while paused): stop and put the volume back
      // rather than tick on, holding it at START_LEVEL; 'playing' starts the fade again if it comes.
      if (v.paused && (v.readyState >= 2)) {
        if (!pausedSince) pausedSince = Date.now();
        else if (Date.now() - pausedSince > 1000) {
          clearTimers(s);
          ramp(v, 1, 0);
          return;
        }
      } else {
        pausedSince = 0;
      }
      const p = gainParam(v);
      if (p && (warm !== 0)) {
        // The equalizer took over: once the sound flows, one sample-accurate curve on its gain. After a warm-up the gain sits
        // at 0, so the curve starts from START_LEVEL as it would have without one.
        if (v.paused || v.seeking || (v.readyState < 3)) return;
        clearTimers(s);
        ramp(v, 1, START_FADE_S * 1000, null, (gainSilenced || (warm === 1)) ? (f, to, k) => rise(START_LEVEL, to, k) : rise);
        return;
      }
      if (p && !gainSilenced) silenceGain(p);
      if (v.paused || v.seeking) return;
      const t = v.currentTime;
      if (t0 < 0) {
        if (!(t > 0) && (v.readyState < 3)) return; // nothing playing yet
        t0 = Math.max(0, t - 0.05);
        // Only a song starting at 0:00 warms up (a few hundredths in is still the start). Anywhere else it is YouTube
        // reloading the stream mid-song (a quality change, a stall) or a resume: the listener already heard a gap there, and
        // 0.6 s more of silence on top only made it longer, so it just fades in.
        if (warm === 0) {
          if (t < 0.3) {
            startAt = 0;
          } else {
            warm = -1;
            trace('start warm-up skipped: began at ' + t.toFixed(2));
            if (p) return; // the gain branch above fades it in on the next step
          }
        }
      }
      if (warm === 0) {
        if (t - t0 < warmS) return; // silent while the sound output gets going
        warm = 1;
        trace('start warm-up: ' + (t - t0).toFixed(2) + 's played silently, back to ' + startAt.toFixed(2) +
          (hidden ? ' (page hidden)' : ''));
        // Marked so the 'waiting' this seek may cause is not reported as buffering (YoutubeWebView#attachListeners).
        v.__fermataWarmSeekAt = Date.now();
        try {
          v.currentTime = startAt;
        } catch (err) { /* element gone */ }
        t0 = -1; // the fade starts once the clock moves again from there
        return;
      }
      const k = Math.min(1, Math.max(0, (t - t0) / START_FADE_S));
      const now = performance.now();
      if (lastTick) maxGap = Math.max(maxGap, now - lastTick);
      lastTick = now;
      ticks++;
      const before = s.level;
      applyElementLevel(v, s, rise(START_LEVEL, 1, k));
      maxJump = Math.max(maxJump, s.level - before);
      if (k >= 1) {
        clearTimers(s);
        trace('start fade: ' + ticks + ' steps, longest gap ' + Math.round(maxGap) + 'ms, biggest step ' +
          Math.round(maxJump * 100) + '%');
      }
    };
    s.timer = setInterval(step, STEP_MS);
    s.startStep = step;
  }

  function silence(v) {
    ramp(v, 0, 0);
  }

  function restoreSilently(v) {
    // Called once the element is paused. The Web Audio gain stage just stays down: nothing
    // persists it, and the next 'play'/'playing' fades it back up anyway. The element's own
    // volume is put back (the player could otherwise persist the lowered value as the user's
    // volume), but only after RESTORE_AFTER_PAUSE_MS -- see there.
    if (gainParam(v)) return;
    const s = state(v);
    if (s.restoreTimer) clearTimeout(s.restoreTimer);
    s.restoreTimer = setTimeout(() => {
      s.restoreTimer = 0;
      if (v.paused && !s.pausing) ramp(v, 1, 0);
    }, RESTORE_AFTER_PAUSE_MS);
  }

  // Live Hall on: pausing should leave its echo ringing, and a fade sits after the reverb in the
  // chain, so it would silence the tail along with everything else. See pause() below.
  function hasReverbTail(v) {
    try {
      const eq = window.FermataEqualizer;
      return !!(eq && (typeof eq.hasReverbTail === 'function') && eq.hasReverbTail(v));
    } catch (err) {
      return false;
    }
  }

  // The page's real visibility: document.hidden is made to always read false for YouTube's own
  // player (see YoutubeWebView#attachListeners), but timers really are throttled while hidden.
  function pageHidden() {
    return window.__fermataPageHidden ? window.__fermataPageHidden() : document.hidden;
  }

  function isAudible(v) {
    return !v.paused && !v.ended && !v.muted && !pageHidden() && (state(v).level > 0.01);
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
    // Not for a new video (onLoadStart): that has its own fade under way.
    if (s.fresh) return;
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
    const fresh = s.fresh;
    s.fresh = false;
    if (fresh && !isAd()) {
      // Its fade is already under way (onLoadStart); started again only if something stopped it.
      if (!s.timer && !s.doneTimer && (s.level < 1)) startFade(v);
      return;
    }
    fadeIn(v, isAd() ? 0 : FADE_IN_MS);
  }

  function onLoadStart(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    const s = state(v);
    s.ending = false;
    s.fresh = true;
    // The new source has played nothing yet: its fade in starts now, so its first sound is heard
    // (see START_LEVEL). Not while the app is fading out to pause.
    if (s.pausing) return;
    if (isAd()) ramp(v, 1, 0);
    else startFade(v);
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
    if (s.startStep) s.startStep(); // a new video's warm-up and fade, when the page's timers lag
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
  document.addEventListener('volumechange', onVolumeChange, true);

  window.FermataFade = {
    configure(c) {
      Object.assign(cfg, c || {});
    },

    // The next new video warms up as long as the first one of a fresh page (see WARM_FIRST_S).
    longWarm() {
      pageFirstStart = true;
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
      // No fade when Live Hall is on (a plain pause, not a stop): the element stops right away
      // and the hall's own tail rings out naturally, so the gain stage is left at full level --
      // restoreSilently() never touches it on the Web Audio path.
      if (!isAudible(v) || (!stop && hasReverbTail(v))) {
        clearTimers(s);
        s.pausing = true;
        finish();
        return;
      }
      s.pausing = true;
      ramp(v, 0, (ms == null) ? FADE_OUT_MS : ms, () => {
        s.doneTimer = setTimeout(() => { s.doneTimer = 0; finish(); }, PAUSE_HOLD_MS);
      });
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
      // Already playing but held down (faded out for a switch that then replayed this video
      // instead, see YoutubeWebView#replay): play() fires no event here, so bring it back now.
      if (!v.paused && (s.level < 1) && !s.timer && !s.ending) {
        fadeIn(v);
        return;
      }
      if (v.paused && !isAd()) silence(v);
    },

    fadeIn
  };
})();
