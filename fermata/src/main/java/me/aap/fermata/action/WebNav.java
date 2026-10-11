package me.aap.fermata.action;

import android.webkit.WebView;

import androidx.annotation.NonNull;

import java.util.Locale;

import me.aap.fermata.ui.view.EffectsUi;

/**
 * Car mode on a web page (the YouTube tab's, the browser's): previous / next move an outline over
 * the page's links and buttons, in the page's own order, and long next presses the outlined one.
 * On YouTube the first press lands on a video (the first one suggested under what's playing).
 * All of it runs in the page, as a script: the elements a tap would reach are found afresh on every
 * press (a page changes under it all the time, YouTube's feed above all), the outline is a fixed
 * box over the page that follows the element as the page scrolls, and fades after a while without
 * a press, like the outline over the app's own lists.
 */
final class WebNav {
	private static final String SCRIPT = """
			(function(cmd, color) {
			var S = window.__zrNav || (window.__zrNav = {});
			var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],' +
				'[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch],' +
				'[tabindex]:not([tabindex="-1"]),[onclick]';
			var vw = window.innerWidth, vh = window.innerHeight;
			function shown(e) {
				var r = e.getBoundingClientRect();
				if ((r.width < 4) || (r.height < 4)) return false;
				if (r.width * r.height > vw * vh * 0.5) return false;
				if (e.disabled || e.closest('[aria-hidden=true],[hidden],[inert]')) return false;
				var cs = getComputedStyle(e);
				return (cs.visibility !== 'hidden') && (cs.display !== 'none') && (cs.opacity !== '0');
			}
			function list() {
				var all = document.querySelectorAll(SEL), out = [], seen = new Set();
				for (var i = 0; i < all.length; i++) {
					var e = all[i];
					if (!shown(e)) continue;
					var p = e.parentElement ? e.parentElement.closest(SEL) : null, inner = false;
					while (p) {
						if (seen.has(p)) { inner = true; break; }
						p = p.parentElement ? p.parentElement.closest(SEL) : null;
					}
					if (inner) continue;
					seen.add(e);
					out.push(e);
				}
				return out;
			}
			function inView(e) {
				var r = e.getBoundingClientRect();
				return (r.bottom > 0) && (r.top < vh) && (r.right > 0) && (r.left < vw);
			}
			function box() {
				var b = S.box;
				if (!b || !b.isConnected) {
					b = S.box = document.createElement('div');
					b.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;' +
						'box-sizing:border-box;border:3px solid ' + color + ';border-radius:12px;' +
						'box-shadow:0 0 0 4px ' + color + '40,0 0 18px ' + color + '80;background:' + color +
						'26;opacity:0;left:0;top:0;width:0;height:0;';
					document.documentElement.appendChild(b);
				}
				return b;
			}
			function place() {
				var e = S.el, b = S.box;
				if (!e || !b) return;
				if (!e.isConnected) { b.style.opacity = '0'; return; }
				var r = e.getBoundingClientRect();
				b.style.left = (r.left - 4) + 'px';
				b.style.top = (r.top - 4) + 'px';
				b.style.width = (r.width + 8) + 'px';
				b.style.height = (r.height + 8) + 'px';
			}
			if (!S.hooked) {
				S.hooked = true;
				var follow = function() {
					if (!S.box || !S.el) return;
					S.box.style.transition = 'opacity .2s';
					requestAnimationFrame(place);
				};
				addEventListener('scroll', follow, true);
				addEventListener('resize', follow);
			}
			function fadeLater() {
				clearTimeout(S.t);
				S.t = setTimeout(function() {
					if (S.box) S.box.style.opacity = '0';
					S.faded = true;
				}, 12000);
			}
			function select(e) {
				S.el = e;
				S.faded = false;
				var b = box();
				b.style.transition = 'opacity .2s,left .15s,top .15s,width .15s,height .15s';
				var r = e.getBoundingClientRect();
				if ((r.top < 64) || (r.bottom > vh - 16) || (r.left < 0) || (r.right > vw)) {
					try { e.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'smooth'}); }
					catch (x) { e.scrollIntoView(); }
				}
				place();
				b.style.opacity = '1';
				fadeLater();
			}
			function flash() {
				var b = S.box;
				if (!b) return;
				b.style.opacity = '1';
				b.animate([{transform: 'scale(1.06)'}, {transform: 'scale(1)'}], 260);
				fadeLater();
			}
			if (cmd === 'clear') {
				clearTimeout(S.t);
				if (S.box) S.box.remove();
				S.box = null;
				S.el = null;
				return 'cleared';
			}
			var cur = (S.el && S.el.isConnected) ? S.el : null;
			if (cmd === 'click') {
				if (!cur || !inView(cur)) cmd = 'next';
				else {
					flash();
					S.el = null;
					setTimeout(function() { if (S.box && !S.el) S.box.style.opacity = '0'; }, 260);
					if (cur.matches('input,textarea,select')) cur.focus();
					else cur.click();
					return 'clicked';
				}
			}
			var dir = (cmd === 'prev') ? -1 : 1;
			if (cur && S.faded && inView(cur)) { select(cur); return 'shown'; }
			var l = list();
			if (!l.length) return 'empty';
			var i = cur ? l.indexOf(cur) : -1;
			if ((i >= 0) && !inView(cur)) i = -1;
			if (i < 0) {
				var vi = -1;
				var host = location.hostname;
				if ((host === 'youtube.com') || host.endsWith('.youtube.com')) {
					// YouTube: straight to a video (the first suggested one under the playing video),
					// not the header's logo and buttons.
					var curV = new URLSearchParams(location.search).get('v');
					var isVid = function(e) {
						var h = e.getAttribute('href') || '';
						return ((h.indexOf('/watch?') >= 0) || (h.indexOf('/shorts/') >= 0)) &&
							!(curV && (h.indexOf(curV) >= 0));
					};
					for (var k = 0; k < l.length; k++) if (isVid(l[k]) && inView(l[k])) { vi = k; break; }
					if (vi < 0) for (k = 0; k < l.length; k++) if (isVid(l[k])) { vi = k; break; }
				}
				if (vi >= 0) i = vi;
				else {
					if (dir > 0) { for (i = 0; (i < l.length) && !inView(l[i]); i++); }
					else { for (i = l.length - 1; (i >= 0) && !inView(l[i]); i--); }
					if ((i < 0) || (i >= l.length)) i = (dir > 0) ? 0 : l.length - 1;
				}
			} else {
				i += dir;
				if ((i < 0) || (i >= l.length)) { flash(); return 'end'; }
			}
			select(l[i]);
			return 'moved';
			})""";

	private WebNav() {
	}

	/** Moves the outline to the next (or previous) link or button. */
	static void move(@NonNull WebView w, boolean next) {
		run(w, next ? "next" : "prev");
	}

	/** Presses the outlined one, or puts the outline on the first if there's none. */
	static void activate(@NonNull WebView w) {
		run(w, "click");
	}

	/** Takes the outline down. */
	static void clear(@NonNull WebView w) {
		run(w, "clear");
	}

	private static void run(WebView w, String cmd) {
		int c = EffectsUi.accent(w.getContext());
		String color = String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
		w.evaluateJavascript(SCRIPT + "('" + cmd + "','" + color + "');",
				r -> me.aap.fermata.util.DiagnosticLog.log("CARNAV", "web " + cmd, r));
	}
}
