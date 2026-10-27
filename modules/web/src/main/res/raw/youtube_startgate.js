(function() {
  if (window.FermataStartGate) return;

  // Start gate: a new video only starts playing once a little of it is buffered ahead, so its first
  // seconds don't play, stall for data and play again (the "play, tiny pause, play" heard at the start of
  // a song). YouTube's player calls play() on the element as soon as it has its first bytes; for a video
  // that has played nothing yet, that call is held here (its promise just stays pending, as it does
  // while the browser itself waits for data) until AHEAD_S seconds are buffered past the playback
  // point, or ENOUGH_AHEAD_S with the browser saying it has enough data, or MAX_WAIT_MS has passed.
  // Nothing is paused: no 'pause' or 'playing' event is made up, the page simply starts a moment later,
  // and the app shows its loading circle meanwhile. Only the first play() after a 'loadstart' is held;
  // a resume, a seek or a later play() is never touched.
  //
  // Switched off by the YouTube setting "Wait for a song to buffer before it starts" (configure({on:
  // false})), which puts the element's own play() straight through again. To take it out entirely,
  // drop this file's injection in YoutubeWebView#injectStartGate.
  const AHEAD_S = 2.0;
  const ENOUGH_AHEAD_S = 1.0;
  const MAX_WAIT_MS = 3000;
  // A play() this long after the 'loadstart' is not the video's start any more (left paused, then played).
  const ARMED_MS = 8000;
  const POLL_MS = 80;

  const cfg = {on: true, traceEvent: 0};
  const origPlay = HTMLMediaElement.prototype.play;

  function ahead(v) {
    const b = v.buffered;
    const t = v.currentTime;
    for (let i = 0; i < b.length; i++) {
      if ((b.start(i) <= t + 0.1) && (b.end(i) > t)) return b.end(i) - t;
    }
    return 0;
  }

  function ready(v) {
    const a = ahead(v);
    if (a >= AHEAD_S) return true;
    if ((v.readyState >= 4) && (a >= ENOUGH_AHEAD_S)) return true;
    // Shorter than the wait (a jingle) or all of it is in: nothing more will come.
    return isFinite(v.duration) && (v.duration > 0) && (v.currentTime + a >= v.duration - 0.1);
  }

  function trace(msg) {
    try {
      if (cfg.traceEvent && window.Fermata && (Date.now() < (window.__fermataTraceUntil || 0))) {
        window.Fermata.event(cfg.traceEvent, 'start gate: ' + msg);
      }
    } catch (err) { /* bridge unavailable */ }
  }

  document.addEventListener('loadstart', function(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    v.__fermataGateArmedAt = Date.now();
    v.__fermataGate = null;
  }, true);

  HTMLMediaElement.prototype.play = function() {
    const v = this;
    const armedAt = v.__fermataGateArmedAt || 0;
    if (!cfg.on || (v.tagName !== 'VIDEO') || !armedAt || (Date.now() - armedAt > ARMED_MS) ||
        window.__fermataAdShowing) {
      return origPlay.apply(v, arguments);
    }
    // A second call while the first is held waits for the same start.
    if (v.__fermataGate) return v.__fermataGate;
    v.__fermataGateArmedAt = 0;
    if (ready(v)) {
      trace('open at once, ahead=' + ahead(v).toFixed(2) + ' ready=' + v.readyState);
      return origPlay.apply(v, arguments);
    }
    const args = arguments;
    const t0 = Date.now();
    trace('holding, ahead=' + ahead(v).toFixed(2) + ' ready=' + v.readyState);
    const gate = new Promise(function(resolve, reject) {
      const tick = function() {
        // The source changed under the wait (a skip): this start is over, the new one has its own gate.
        if (v.__fermataGate !== gate) {
          resolve();
          return;
        }
        const waited = Date.now() - t0;
        if (!cfg.on || ready(v) || (waited >= MAX_WAIT_MS)) {
          v.__fermataGate = null;
          trace('open after ' + waited + 'ms, ahead=' + ahead(v).toFixed(2) + ' ready=' + v.readyState);
          let p;
          try {
            p = origPlay.apply(v, args);
          } catch (err) {
            reject(err);
            return;
          }
          if (p && (typeof p.then === 'function')) p.then(resolve, reject);
          else resolve();
          return;
        }
        setTimeout(tick, POLL_MS);
      };
      setTimeout(tick, POLL_MS);
    });
    v.__fermataGate = gate;
    return gate;
  };

  // The other half: YouTube's own player sometimes pauses the element a moment after a new video starts
  // (its layout or state settling), and the app only heard of it through the bridge, then asked it to play
  // again: the "play, tiny pause, play". Within RESUME_WINDOW_MS of a video's first 'playing', a pause nobody
  // asked for (not the app's: FermataFade.pause marks those; not a tap: no user activation) is undone right
  // here, in the same task, and its 'pause' and the 'play' that follows never reach the app or the fade. At
  // most MAX_RESUMES per video, so a pause YouTube really insists on still goes through to the app's own
  // handling (YoutubeMediaEngine#paused).
  const RESUME_WINDOW_MS = 1500;
  const MAX_RESUMES = 2;

  window.addEventListener('loadstart', function(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO')) return;
    v.__fermataFirstPlayingAt = 0;
    v.__fermataResumes = 0;
    v.__fermataStarting = true;
  }, true);

  window.addEventListener('playing', function(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO') || !v.__fermataStarting) return;
    v.__fermataStarting = false;
    v.__fermataFirstPlayingAt = Date.now();
    watchStart(v);
  }, true);

  // A start that says 'playing' but does not move: seen in a log, a song held at 0:00 for 5 s after 'playing' (the
  // element thought it had plenty buffered), then a wait and a second start, heard as a stutter. 'timeupdate' does not
  // fire while it is stuck, so this watches the clock: for the first WATCH_MS of a video, when the time has not moved
  // for STUCK_MS while it should be playing with data there, the pipeline is nudged with a seek to where it is (the
  // usual cure for a media pipeline stuck at its start), at most twice. Without data it is only traced (the network).
  const WATCH_MS = 8000;
  const STUCK_MS = 700;
  const MAX_NUDGES = 2;

  function watchStart(v) {
    if (v.__fermataWatch) clearInterval(v.__fermataWatch);
    const src = v.currentSrc;
    const t0 = Date.now();
    let lastT = v.currentTime;
    let movedAt = Date.now();
    let nudges = 0;
    let traced = false;
    v.__fermataWatch = setInterval(function() {
      const now = Date.now();
      if ((now - t0 > WATCH_MS) || (v.currentSrc !== src) || v.ended) {
        clearInterval(v.__fermataWatch);
        v.__fermataWatch = 0;
        return;
      }
      if (v.paused || v.seeking) {
        lastT = v.currentTime;
        movedAt = now;
        return;
      }
      const t = v.currentTime;
      if (Math.abs(t - lastT) > 0.01) {
        lastT = t;
        movedAt = now;
        traced = false;
        return;
      }
      if (now - movedAt < STUCK_MS) return;
      const a = ahead(v);
      if (cfg.on && (v.readyState >= 3) && (a > 0.3) && (nudges < MAX_NUDGES)) {
        nudges++;
        trace('stuck at t=' + t.toFixed(2) + ' for ' + (now - movedAt) + 'ms with ' + a.toFixed(1) +
          's buffered: nudged (' + nudges + ')');
        try {
          v.currentTime = t;
        } catch (err) { /* element gone */ }
        movedAt = now;
      } else if (!traced) {
        traced = true;
        trace('no progress at t=' + t.toFixed(2) + ' for ' + (now - movedAt) + 'ms, ready=' + v.readyState +
          ' ahead=' + a.toFixed(1) + (cfg.on ? '' : ' (start gate off)'));
      }
    }, 150);
  }

  window.addEventListener('pause', function(e) {
    const v = e.target;
    if (!cfg.on || !v || (v.tagName !== 'VIDEO') || v.ended || window.__fermataAdShowing) return;
    const now = Date.now();
    const started = v.__fermataFirstPlayingAt || 0;
    if (!started || (now - started > RESUME_WINDOW_MS)) return;
    if (now - (v.__fermataAppPauseAt || 0) < 2000) return;
    if (navigator.userActivation && navigator.userActivation.isActive) return;
    if ((v.__fermataResumes || 0) >= MAX_RESUMES) return;
    v.__fermataResumes = (v.__fermataResumes || 0) + 1;
    e.stopImmediatePropagation();
    v.__fermataQuietPlay = true;
    trace('the page paused the start, played on at once (' + v.__fermataResumes + ') t=' + v.currentTime.toFixed(2));
    try {
      const p = origPlay.call(v);
      if (p && p.catch) p.catch(function() { v.__fermataQuietPlay = false; });
    } catch (err) {
      v.__fermataQuietPlay = false;
    }
  }, true);

  window.addEventListener('play', function(e) {
    const v = e.target;
    if (!v || (v.tagName !== 'VIDEO') || !v.__fermataQuietPlay) return;
    v.__fermataQuietPlay = false;
    e.stopImmediatePropagation();
  }, true);

  window.FermataStartGate = {
    configure(c) {
      Object.assign(cfg, c || {});
    }
  };
})();
