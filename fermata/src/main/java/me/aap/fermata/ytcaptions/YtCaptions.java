package me.aap.fermata.ytcaptions;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.aap.fermata.spotify.Http;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;

/**
 * The captions (subtitles) of a YouTube video, as a list of timed lines: what the Music tab shows
 * as lyrics and the fullscreen video shows as captions. Read from the same public "InnerTube"
 * {@code player} endpoint the YouTube apps use (no API key, no quota; see {@code YtComments} for the
 * comments): it names the video's caption tracks, and each track's address returns the lines as
 * {@code json3}.
 * <p>
 * YouTube hands out the addresses of tracks only to some of its apps and changes which, so the
 * apps in {@link #CLIENTS} are tried one after another until one of them gives captions that read.
 * When captions suddenly stop loading everywhere, that list is the first thing to look at (the
 * Diagnostic log says which app answered what, under CAPTIONS).
 * <p>
 * All methods block -- call them on a background thread.
 */
public final class YtCaptions {
	private static final String PLAYER_URL =
			"https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

	private YtCaptions() {
	}

	/** The way one of YouTube's apps introduces itself. */
	private static final class Client {
		final String name;
		final String version;
		final String headerId;
		final String userAgent;
		final String extra;

		Client(String name, String version, String headerId, String userAgent, String extra) {
			this.name = name;
			this.version = version;
			this.headerId = headerId;
			this.userAgent = userAgent;
			this.extra = extra;
		}
	}

	private static final Client[] CLIENTS = {
			new Client("ANDROID", "20.10.38", "3",
					"com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
					"\"osName\":\"Android\",\"osVersion\":\"14\",\"androidSdkVersion\":34"),
			new Client("WEB_EMBEDDED_PLAYER", "2.20260708.00.00", "56",
					"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", "\"clientScreen\":\"EMBED\""),
			new Client("IOS", "20.10.4", "5",
					"com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
					"\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\"," +
							"\"osVersion\":\"18.3.2.22D82\""),
			new Client("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85",
					"Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
							"Version/15.4 Safari/605.1.15",
					"\"clientScreen\":\"EMBED\""),
	};

	/** One timed line. */
	public static final class Cue {
		/** Milliseconds from the start of the video. */
		public final long start;
		/** When the line goes away again (never later than the next line's start). */
		public final long end;
		public final String text;

		public Cue(long start, long end, String text) {
			this.start = start;
			this.end = end;
			this.text = text;
		}
	}

	/** A caption track the video offers. */
	public static final class Track {
		public final String language;
		public final String name;
		/** Written by YouTube's speech recognition rather than by a person. */
		public final boolean generated;
		final String url;
		/** YouTube can translate this track into other languages. */
		final boolean translatable;
		/** Set when the lines are YouTube's translation of another language's track. */
		@Nullable
		public final String translatedFrom;

		Track(String language, String name, boolean generated, String url) {
			this(language, name, generated, url, false, null);
		}

		Track(String language, String name, boolean generated, String url, boolean translatable,
					@Nullable String translatedFrom) {
			this.language = language;
			this.name = name;
			this.generated = generated;
			this.url = url;
			this.translatable = translatable;
			this.translatedFrom = translatedFrom;
		}

		/** This track as YouTube translates it into {@code lang}. */
		Track translatedTo(String lang) {
			return new Track(lang, name, false, url + "&tlang=" + lang, false, language);
		}
	}

	/** What a video's captions came to. */
	public static final class Result {
		/** The track the lines are from, null when the video has none. */
		@Nullable
		public final Track track;
		public final List<Cue> cues;

		Result(@Nullable Track track, List<Cue> cues) {
			this.track = track;
			this.cues = cues;
		}

		public boolean isEmpty() {
			return cues.isEmpty();
		}
	}

	static final Result NONE = new Result(null, Collections.emptyList());

	/** Apps YouTube asked to sign in, and until when they are not asked again (uptime). */
	private static final java.util.Map<String, Long> signInUntil = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long SIGN_IN_SKIP_MS = 30 * 60_000L;

	/**
	 * The captions of {@code videoId} from the YouTube page playing it (see MusicPlayer#youtubePageCaptions), or null
	 * when the page is not playing it, has none or could not fetch them.
	 */
	@Nullable
	private static Result fromPage(String videoId, String want, boolean allowGenerated) {
		String[] got;
		try {
			got = me.aap.fermata.addon.music.MusicPlayer.youtubePageCaptions(videoId, want, allowGenerated);
		} catch (Throwable ex) {
			DiagnosticLog.w(LogCat.CAPTIONS, "page captions failed", "id=" + videoId, ex);
			return null;
		}
		if (got == null) return null;
		if ((got[0] == null) || got[0].trim().isEmpty()) {
			DiagnosticLog.log(LogCat.CAPTIONS, "page captions: empty track", "id=" + videoId, "detail=" + got[1]);
			return null;
		}
		try {
			List<Cue> cues = parse(got[0]);
			if (cues.isEmpty()) {
				DiagnosticLog.log(LogCat.CAPTIONS, "page captions: no lines", "id=" + videoId, "detail=" + got[1]);
				return null;
			}
			boolean asr = "1".equals(got[2]);
			DiagnosticLog.log(LogCat.CAPTIONS, "page captions loaded", "id=" + videoId, "detail=" + got[1], "kind=" + (asr ? "auto" : "manual"), "lines=" + cues.size());
			return new Result(new Track(got[1], got[1], asr, ""), cues);
		} catch (IOException ex) {
			DiagnosticLog.w(LogCat.CAPTIONS, "page captions unreadable", "id=" + videoId, ex);
			return null;
		}
	}

	/**
	 * @param lang         the language to prefer, a code like "en" (empty: the phone's)
	 * @param allowGenerated whether captions written by speech recognition may be used when there are
	 *                     no others
	 * @return the video's captions; {@link Result#isEmpty()} when it has none
	 */
	public static Result load(String videoId, @Nullable String lang, boolean allowGenerated)
			throws IOException {
		Result base = loadBase(videoId, lang, allowGenerated);
		try {
			return withExtras(videoId, base);
		} catch (RuntimeException ex) {
			DiagnosticLog.w(LogCat.CAPTIONS, "extra lines failed", "id=" + videoId, ex);
			return base;
		}
	}

	private static Result loadBase(String videoId, @Nullable String lang, boolean allowGenerated)
			throws IOException {
		String want = ((lang == null) || lang.trim().isEmpty()) ?
				Locale.getDefault().getLanguage() : lang.trim().toLowerCase(Locale.ROOT);
		// First the YouTube page itself, when it is the video it plays: it is a signed-in browser session with
		// everything YouTube asks for, where the apps below are more and more often asked to sign in.
		Result fromPage = fromPage(videoId, want, allowGenerated);
		boolean tmiss = MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.CAPTIONS_TRANSLATE_MISSING);
		if (fromPage != null) return fromPage;
		IOException last = null;
		boolean answered = false;
		for (Client c : CLIENTS) {
			Long skip = signInUntil.get(c.name);
			if ((skip != null) && (android.os.SystemClock.uptimeMillis() < skip)) continue;
			try {
				List<Track> tracks = tracks(c, videoId);
				if (tracks.isEmpty()) {
					DiagnosticLog.log(LogCat.CAPTIONS, "no tracks", "client=" + c.name, "id=" + videoId);
					continue;
				}
				answered = true;
				Track t = choose(tracks, want, allowGenerated, tmiss);
				if (t == null) {
					DiagnosticLog.log(LogCat.CAPTIONS, "no usable track", "client=" + c.name, "id=" + videoId, "tracks=" + tracks.size());
					continue;
				}
				List<Cue> cues = fetch(c, t);
				if (cues.isEmpty()) {
					DiagnosticLog.log(LogCat.CAPTIONS, "empty track", "client=" + c.name, "id=" + videoId, "lang=" + t.language);
					continue;
				}
				DiagnosticLog.log(LogCat.CAPTIONS, "captions loaded", "client=" + c.name, "id=" + videoId, "lang=" + t.language, "kind=" + (t.generated ? "auto" : "manual"), "lines=" + cues.size());
				return new Result(t, cues);
			} catch (IOException ex) {
				DiagnosticLog.w(LogCat.CAPTIONS, "captions failed", "client=" + c.name, "id=" + videoId, ex);
				last = ex;
				// Asked to sign in: it will be asked again for every video for a while (YouTube decides that per
				// connection), so this app is left out for a while instead of costing a request per song.
				if (String.valueOf(ex.getMessage()).contains("sign in")) {
					signInUntil.put(c.name, android.os.SystemClock.uptimeMillis() + SIGN_IN_SKIP_MS);
				}
				// Too many requests: the next app would only be refused too, and every further request
				// keeps the limit on for longer (and takes from the bandwidth the song plays on).
				if (String.valueOf(ex.getMessage()).contains("HTTP 429")) break;
			}
		}
		// Every app failed to talk to YouTube: that is an error, not a video without captions.
		if (!answered && (last != null)) throw last;
		return NONE;
	}

	/**
	 * The video's storyboard spec (the sheets of small pictures YouTube's own seek bar shows), from
	 * the same player request as the captions; null when no app gives one. See YtStoryboard.
	 */
	@Nullable
	static String storyboardSpec(String videoId) {
		for (Client c : CLIENTS) {
			try {
				JSONObject json = player(c, videoId);
				JSONObject sb = json.optJSONObject("storyboards");
				JSONObject r = (sb == null) ? null : sb.optJSONObject("playerStoryboardSpecRenderer");
				String spec = (r == null) ? null : r.optString("spec", "");
				if ((spec != null) && !spec.isEmpty()) {
					DiagnosticLog.log(LogCat.SEEK, "storyboard found", "client=" + c.name, "id=" + videoId);
					return spec;
				}
				DiagnosticLog.log(LogCat.SEEK, "no storyboard", "client=" + c.name, "id=" + videoId);
			} catch (IOException ex) {
				DiagnosticLog.w(LogCat.SEEK, "storyboard failed", "client=" + c.name, "id=" + videoId, ex);
				if (String.valueOf(ex.getMessage()).contains("HTTP 429")) break;
			}
		}
		return null;
	}

	/**
	 * The song's lines with what the settings add under each: its Latin letters (Korean and Japanese, see {@link Romanizer})
	 * and YouTube's translation into another language. A line comes first, then its romanization, then the translation.
	 */
	private static Result withExtras(String videoId, Result base) {
		MainActivityPrefs p = MainActivityPrefs.get();
		boolean romaji = p.getBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI);
		String target = p.getStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION).trim().toLowerCase(Locale.ROOT);
		if ((base.track == null) || base.cues.isEmpty() || (!romaji && target.isEmpty())) return base;
		List<Cue> second = null;
		String own = base.track.language.toLowerCase(Locale.ROOT);
		if (!target.isEmpty() && !own.equals(target) && !own.startsWith(target + "-")) second = translationCues(videoId, target);
		List<Cue> out = new ArrayList<>(base.cues.size());
		int j = 0;
		for (Cue c : base.cues) {
			StringBuilder sb = new StringBuilder(c.text);
			if (romaji) {
				String r = Romanizer.romanize(c.text);
				if (r != null) sb.append('\n').append(r);
			}
			if (second != null) {
				// The translated cue that starts nearest to this one (both lists are in time order).
				while ((j + 1 < second.size()) &&
						(Math.abs(second.get(j + 1).start - c.start) <= Math.abs(second.get(j).start - c.start))) j++;
				if ((j < second.size()) && (Math.abs(second.get(j).start - c.start) <= 2500)) {
					String t = second.get(j).text;
					if (!t.equalsIgnoreCase(c.text)) sb.append('\n').append(t);
				}
			}
			out.add(new Cue(c.start, c.end, sb.toString()));
		}
		return new Result(base.track, out);
	}

	/** The video's captions translated by YouTube into {@code lang}, null when that cannot be had. */
	@Nullable
	private static List<Cue> translationCues(String videoId, String lang) {
		for (Client c : CLIENTS) {
			Long skip = signInUntil.get(c.name);
			if ((skip != null) && (android.os.SystemClock.uptimeMillis() < skip)) continue;
			try {
				List<Track> tracks = tracks(c, videoId);
				Track src = pick(tracks, null, false);
				if (src == null) src = pick(tracks, null, true);
				if ((src == null) || !src.translatable) continue;
				List<Cue> cues = fetch(c, src.translatedTo(lang));
				if (!cues.isEmpty()) return cues;
			} catch (IOException ex) {
				DiagnosticLog.w(LogCat.CAPTIONS, "translation failed", "client=" + c.name, "id=" + videoId, ex);
				if (String.valueOf(ex.getMessage()).contains("HTTP 429")) break;
			}
		}
		return null;
	}

	/** A manual track in the wanted language, else in English, else any; then the same of generated. */
	@Nullable
	private static Track choose(List<Track> tracks, String want, boolean allowGenerated, boolean translateMissing) {
		// The wanted language first, written by a person or else by speech recognition. Only then another language, and
		// first of all that one translated by YouTube: a video with Arabic captions by hand and English ones by speech
		// recognition used to give the Arabic, because any manual track came before any generated one.
		Track t = pick(tracks, want, false);
		if ((t == null) && allowGenerated) t = pick(tracks, want, true);
		if ((t == null) && translateMissing) {
			Track src = pick(tracks, null, false);
			if ((src == null) && allowGenerated) src = pick(tracks, null, true);
			if ((src != null) && src.translatable) t = src.translatedTo(want);
		}
		if (t == null) t = pick(tracks, "en", false);
		if ((t == null) && allowGenerated) t = pick(tracks, "en", true);
		if (t == null) t = pick(tracks, null, false);
		if ((t == null) && allowGenerated) t = pick(tracks, null, true);
		return t;
	}

	@Nullable
	private static Track pick(List<Track> tracks, @Nullable String lang, boolean generated) {
		Track any = null;
		for (Track t : tracks) {
			if (t.generated != generated) continue;
			if (lang == null) return t;
			String tl = t.language.toLowerCase(Locale.ROOT);
			// "en" also takes "en-GB"; a generated "a.en" style code never reaches here (see tracks()).
			if (tl.equals(lang)) return t;
			if (tl.startsWith(lang + "-") && (any == null)) any = t;
		}
		return any;
	}

	private static List<Track> tracks(Client c, String videoId) throws IOException {
		JSONObject json = player(c, videoId);
		List<Track> l = new ArrayList<>();
		try {
			JSONObject status = json.optJSONObject("playabilityStatus");
			if ((status != null) && "LOGIN_REQUIRED".equals(status.optString("status"))) {
				throw new IOException("YouTube asked for a sign in");
			}
			JSONObject caps = json.optJSONObject("captions");
			JSONObject r = (caps == null) ? null : caps.optJSONObject("playerCaptionsTracklistRenderer");
			JSONArray arr = (r == null) ? null : r.optJSONArray("captionTracks");
			if (arr == null) return l;
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.getJSONObject(i);
				String url = o.optString("baseUrl");
				if (url.isEmpty()) continue;
				String code = o.optString("languageCode");
				boolean asr = "asr".equals(o.optString("kind"));
				JSONObject n = o.optJSONObject("name");
				String name = (n == null) ? code : n.optString("simpleText", code);
				l.add(new Track(code, name, asr, url, o.optBoolean("isTranslatable", false), null));
			}
		} catch (JSONException ex) {
			throw new IOException("Unexpected YouTube response", ex);
		}
		return l;
	}

	private static JSONObject player(Client c, String videoId) throws IOException {
		try {
			JSONObject client = new JSONObject("{" + c.extra + "}")
					.put("clientName", c.name)
					.put("clientVersion", c.version)
					.put("hl", "en");
			JSONObject context = new JSONObject().put("client", client);
			if ("TVHTML5_SIMPLY_EMBEDDED_PLAYER".equals(c.name)) {
				context.put("thirdParty", new JSONObject()
						.put("embedUrl", "https://www.youtube.com/watch?v=" + videoId));
			}
			JSONObject body = new JSONObject()
					.put("context", context)
					.put("videoId", videoId)
					.put("contentCheckOk", true)
					.put("racyCheckOk", true);
			Map<String, String> h = new HashMap<>();
			h.put("User-Agent", c.userAgent);
			h.put("X-YouTube-Client-Name", c.headerId);
			h.put("X-YouTube-Client-Version", c.version);
			h.put("Origin", "https://www.youtube.com");
			h.put("Cookie", "SOCS=CAI");
			Http.Response r = Http.post(PLAYER_URL, body.toString(), h);
			if (!r.isOk()) throw new IOException("YouTube returned HTTP " + r.code);
			return new JSONObject(r.body);
		} catch (JSONException ex) {
			throw new IOException("Unexpected YouTube response", ex);
		}
	}

	private static List<Cue> fetch(Client c, Track t) throws IOException {
		String url = t.url.replaceAll("[&?]fmt=[^&]*", "") + "&fmt=json3";
		Map<String, String> h = new HashMap<>();
		h.put("User-Agent", c.userAgent);
		h.put("Origin", "https://www.youtube.com");
		h.put("Cookie", "SOCS=CAI");
		Http.Response r = Http.get(url, h);
		if (!r.isOk()) throw new IOException("Captions returned HTTP " + r.code);
		// An empty body (what a refused request looks like) simply has no lines: the next app is tried.
		if (r.body.trim().isEmpty()) return Collections.emptyList();
		return parse(r.body);
	}

	/** The lines of a {@code json3} track. */
	static List<Cue> parse(String json3) throws IOException {
		List<long[]> times = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		try {
			JSONArray events = new JSONObject(json3).optJSONArray("events");
			if (events == null) return Collections.emptyList();
			for (int i = 0; i < events.length(); i++) {
				JSONObject e = events.getJSONObject(i);
				JSONArray segs = e.optJSONArray("segs");
				if (segs == null) continue;
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < segs.length(); j++) sb.append(segs.getJSONObject(j).optString("utf8"));
				String text = sb.toString().replace('\n', ' ').replaceAll("\\s+", " ").trim();
				if (text.isEmpty()) continue;
				long start = e.optLong("tStartMs");
				long dur = e.optLong("dDurationMs", 3000);
				times.add(new long[]{start, start + Math.max(dur, 500)});
				texts.add(text);
			}
		} catch (JSONException ex) {
			throw new IOException("Unexpected captions format", ex);
		}

		// In order of appearance; a line goes away when the next one comes (rolling captions overlap).
		Integer[] order = new Integer[times.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Long.compare(times.get(a)[0], times.get(b)[0]));
		List<Cue> cues = new ArrayList<>(order.length);
		for (int k = 0; k < order.length; k++) {
			long[] t = times.get(order[k]);
			long end = t[1];
			if (k + 1 < order.length) end = Math.min(end, times.get(order[k + 1])[0]);
			if (end <= t[0]) continue;
			cues.add(new Cue(t[0], end, texts.get(order[k])));
		}
		return cues;
	}
}
