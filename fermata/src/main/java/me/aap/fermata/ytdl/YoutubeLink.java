package me.aap.fermata.ytdl;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the YouTube video a link from another app points at: a VIEW intent on a youtu.be or
 * youtube.com address (or a vnd.youtube: one), or a SEND intent whose text holds such a link (what
 * the YouTube app's Share sheet sends).
 */
public final class YoutubeLink {
	private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
	private static final Pattern IN_TEXT =
			Pattern.compile("(?:https?://)?(?:[\\w-]+\\.)*(?:youtu\\.be|youtube\\.com)/\\S+");
	private static final String[] PATH_PREFIXES = {"shorts", "embed", "live", "v"};

	private YoutubeLink() {
	}

	/** The video id the intent asks to play, or null if it isn't a YouTube video link. */
	@Nullable
	public static String videoIdOf(@Nullable Intent intent) {
		if (intent == null) return null;
		String action = intent.getAction();
		if (Intent.ACTION_VIEW.equals(action)) {
			return videoIdOf(intent.getData());
		} else if (Intent.ACTION_SEND.equals(action)) {
			CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
			return (text == null) ? null : videoIdInText(text.toString());
		}
		return null;
	}

	/** The video id of a YouTube address, or null. */
	@Nullable
	public static String videoIdOf(@Nullable Uri u) {
		if (u == null) return null;
		String scheme = u.getScheme();
		if ("vnd.youtube".equals(scheme)) {
			String id = u.getSchemeSpecificPart();
			if (id.startsWith("//")) id = id.substring(2);
			return validId(id);
		}
		if (!"http".equals(scheme) && !"https".equals(scheme)) return null;

		String host = u.getHost();
		if (host == null) return null;
		if (host.startsWith("www.")) host = host.substring(4);

		if ("youtu.be".equals(host)) {
			return validId(firstSegment(u.getPath()));
		}
		if (!"youtube.com".equals(host) && !host.endsWith(".youtube.com")) return null;

		String path = u.getPath();
		if (path == null) return null;
		if (path.equals("/watch") || path.startsWith("/watch/")) {
			return validId(u.getQueryParameter("v"));
		}
		for (String p : PATH_PREFIXES) {
			if (path.startsWith("/" + p + "/")) return validId(segment(path, 1));
		}
		return null;
	}

	/** The video id of the first YouTube link in {@code text}, or null. */
	@Nullable
	static String videoIdInText(String text) {
		Matcher m = IN_TEXT.matcher(text);
		while (m.find()) {
			String link = m.group();
			if (!link.startsWith("http")) link = "https://" + link;
			String id = videoIdOf(Uri.parse(link));
			if (id != null) return id;
		}
		return null;
	}

	@Nullable
	private static String validId(@Nullable String id) {
		return ((id != null) && VIDEO_ID.matcher(id).matches()) ? id : null;
	}

	@Nullable
	private static String firstSegment(@Nullable String path) {
		return segment(path, 0);
	}

	/** The {@code n}th path segment (0 based), with anything after a '?' or '&' cut off. */
	@Nullable
	private static String segment(@Nullable String path, int n) {
		if (path == null) return null;
		String[] parts = path.split("/");
		int i = n + 1; // parts[0] is the empty string before the leading slash
		if (i >= parts.length) return null;
		String s = parts[i];
		int end = s.length();
		for (int k = 0; k < end; k++) {
			char c = s.charAt(k);
			if ((c == '?') || (c == '&')) {
				end = k;
				break;
			}
		}
		return s.substring(0, end);
	}
}
