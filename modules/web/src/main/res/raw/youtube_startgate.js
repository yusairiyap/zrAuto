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

  window.FermataStartGate = {
    configure(c) {
      Object.assign(cfg, c || {});
    }
  };
})();
