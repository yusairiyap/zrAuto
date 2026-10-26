package me.aap.fermata.ytcomments;

import android.webkit.CookieManager;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.spotify.Http;

/**
 * The comments of a YouTube video, read and posted through the same public "InnerTube" endpoints
 * youtube.com's own page uses (no API key, no quota; see {@code YoutubeSearch} for the search). Only
 * what the card shows is picked out of the (large, often changing) responses, by searching them for
 * a key rather than following a fixed path, so a small change in their layout doesn't break it.
 * <p>
 * Reading is anonymous unless the user is signed in to YouTube in the app's browser: its cookies are
 * then sent too, which is what makes YouTube include the "write a comment" and "reply" parameters in
 * the answer, and what posting needs (see {@link #isLoggedIn()}).
 * <p>
 * All methods block -- call them on a background thread.
 */
public final class YtComments {
	private static final String ORIGIN = "https://www.youtube.com";
	private static final String API = ORIGIN + "/youtubei/v1/";
	private static final String CLIENT_VERSION = "2.20250101.00.00";

	private YtComments() {
	}

	/** One comment (or reply). */
	public static final class Comment {
		public final String id;
		public final String author;
		@Nullable
		public final String avatarUrl;
		public final String text;
		/** "2 years ago". */
		public final String published;
		/** "1.2K", empty for none. */
		public final String likes;
		public final boolean creator;
		public final boolean pinned;
		public final boolean hearted;
		public final boolean reply;
		/** Replies it has, as YouTube words it ("12"); empty for none. */
		public final String replyCount;
		/** Loads its replies, null if it has none. */
		@Nullable
		public final String repliesToken;
		/** What posting a reply to it needs, null if it can't be replied to (or not signed in). */
		@Nullable
		public final String replyParams;

		Comment(String id, String author, @Nullable String avatarUrl, String text, String published,
						String likes, boolean creator, boolean pinned, boolean hearted, boolean reply,
						String replyCount, @Nullable String repliesToken, @Nullable String replyParams) {
			this.id = id;
			this.author = author;
			this.avatarUrl = avatarUrl;
			this.text = text;
			this.published = published;
			this.likes = likes;
			this.creator = creator;
			this.pinned = pinned;
			this.hearted = hearted;
			this.reply = reply;
			this.replyCount = replyCount;
			this.repliesToken = repliesToken;
			this.replyParams = replyParams;
		}
	}

	/** A batch of comments, and what comes with the first one. */
	public static final class Page {
		public final List<Comment> comments = new ArrayList<>();
		/** Loads the next batch, null at the end. */
		@Nullable
		public String next;
		/** The comment count as YouTube words it ("1,234"), when the page says. */
		@Nullable
		public String count;
		/** What posting a new comment needs, null when not signed in (or the video allows none). */
		@Nullable
		public String createParams;
		/** Sorting by top comments / newest first: the tokens to load the list sorted that way. */
		@Nullable
		public String sortTop;
		@Nullable
		public String sortNew;
		/** The video has no comments section at all. */
		public boolean disabled;
		/** The video's views and likes, -1 when the watch page doesn't say (first batch only). */
		public long views = -1;
		public long likes = -1;
	}

	// ---------------------------------------------------------------------------------------------
	// Signed in or not

	/**
	 * Whether the browser the app plays YouTube in is signed in to a Google account (not in Private
	 * Mode, which uses a browser profile of its own that nothing is signed in to).
	 */
	public static boolean isLoggedIn() {
		return sapisid(cookies()) != null;
	}

	private static String cookies() {
		try {
			String c = CookieManager.getInstance().getCookie(ORIGIN);
			return (c == null) ? "" : c;
		} catch (Throwable ex) {
			// No WebView on this device.
			return "";
		}
	}

	@Nullable
	private static String cookie(String all, String name) {
		for (String part : all.split(";")) {
			String p = part.trim();
			if (p.startsWith(name + "=") && (p.length() > name.length() + 1)) {
				return p.substring(name.length() + 1);
			}
		}
		return null;
	}

	@Nullable
	private static String sapisid(String all) {
		String s = cookie(all, "SAPISID");
		return (s != null) ? s : cookie(all, "__Secure-3PAPISID");
	}

	/** The "Authorization" value YouTube's page sends: hashes of the account's SAPISID cookies. */
	@Nullable
	private static String authorization(String all) {
		long ts = System.currentTimeMillis() / 1000;
		StringBuilder sb = new StringBuilder();
		String[][] names = {{"SAPISIDHASH", "SAPISID"}, {"SAPISID1PHASH", "__Secure-1PAPISID"},
				{"SAPISID3PHASH", "__Secure-3PAPISID"}};
		for (String[] n : names) {
			String v = cookie(all, n[1]);
			if ((v == null) && n[1].equals("SAPISID")) v = cookie(all, "__Secure-3PAPISID");
			if (v == null) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(n[0]).append(' ').append(ts).append('_').append(sha1(ts + " " + v + " " + ORIGIN));
		}
		return (sb.length() == 0) ? null : sb.toString();
	}

	private static String sha1(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
			StringBuilder sb = new StringBuilder(d.length * 2);
			for (byte b : d) sb.append(String.format("%02x", b & 0xff));
			return sb.toString();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Reading

	/** {views, likes} of {@code videoId} (each -1 where YouTube doesn't say): one request, no comments. */
	public static long[] stats(String videoId) throws IOException {
		JSONObject watch = call("next", body -> body.put("videoId", videoId));
		return new long[]{views(watch), likes(watch)};
	}

	/** The first batch of {@code videoId}'s comments, top comments first. */
	public static Page load(String videoId) throws IOException {
		JSONObject watch = call("next", body -> body.put("videoId", videoId));
		String token = commentsToken(watch);
		Page p;
		if (token == null) {
			p = new Page();
			p.disabled = true;
		} else {
			p = more(token);
		}
		p.views = views(watch);
		p.likes = likes(watch);
		return p;
	}

	/**
	 * The view count of the watch page: its raw number, else the digits of "1,234,567 views" (the
	 * full text, never the shortened "1.2M views"). -1 when it can't be told: a 0 there (some
	 * responses carry an empty raw count) isn't believed.
	 */
	private static long views(JSONObject watch) {
		JSONObject o = findObject(watch, "videoViewCountRenderer");
		JSONObject r = (o == null) ? null : o.optJSONObject("videoViewCountRenderer");
		if (r != null) {
			long n = number(r.optString("originalViewCount", null));
			if (n > 0) return n;
			n = number(text(r.optJSONObject("viewCount")));
			if (n > 0) return n;
		}
		// The newer description panel: a factoid about views.
		JSONObject f = findViewsFactoid(watch);
		if (f != null) {
			String acc = findString(f, "accessibilityText");
			long n = number(((acc != null) && acc.contains("view")) ? acc : null);
			if (n > 0) return n;
		}
		return -1;
	}

	@Nullable
	private static JSONObject findViewsFactoid(@Nullable Object node) {
		if (node instanceof JSONObject o) {
			JSONObject fr = o.optJSONObject("viewCountFactoidRenderer");
			if (fr == null) fr = o.optJSONObject("factoidRenderer");
			if ((fr != null) && String.valueOf(findString(fr, "accessibilityText")).contains("view")) {
				return fr;
			}
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				JSONObject r = findViewsFactoid(o.opt(it.next()));
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				JSONObject r = findViewsFactoid(a.opt(i));
				if (r != null) return r;
			}
		}
		return null;
	}

	/**
	 * The like count: the exact number of the newer like button's entity, else the "along with
	 * 1,234 other people" of its accessibility text. -1 when likes are hidden.
	 */
	private static long likes(JSONObject watch) {
		long n = number(findString(watch, "likeCountIfIndifferentNumber"));
		if (n >= 0) return n;
		String a = findStringContaining(watch, "other people");
		if (a == null) return -1;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9][0-9,.\\u00a0 ]*) other").matcher(a);
		return m.find() ? number(m.group(1)) : -1;
	}

	private static long number(@Nullable String s) {
		if (s == null) return -1;
		String d = s.replaceAll("[^0-9]", "");
		if (d.isEmpty() || (d.length() > 15)) return -1;
		return Long.parseLong(d);
	}

	/** The first string value anywhere below {@code node} that contains {@code part}. */
	@Nullable
	private static String findStringContaining(@Nullable Object node, String part) {
		if (node instanceof JSONObject o) {
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				Object v = o.opt(it.next());
				if ((v instanceof String str) && str.contains(part)) return str;
				String r = findStringContaining(v, part);
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				Object v = a.opt(i);
				if ((v instanceof String str) && str.contains(part)) return str;
				String r = findStringContaining(v, part);
				if (r != null) return r;
			}
		}
		return null;
	}

	/** 999, 1.2K, 34K, 1.5M, 2.1B: a count shortened the way YouTube does. */
	public static String shortCount(long n) {
		if (n < 1000) return String.valueOf(n);
		String[] units = {"K", "M", "B", "T"};
		double v = n;
		int u = -1;
		while ((v >= 1000) && (u < units.length - 1)) {
			v /= 1000;
			u++;
		}
		String num = (v < 10) ? String.format(java.util.Locale.ROOT, "%.1f", Math.floor(v * 10) / 10) :
				String.valueOf((long) Math.floor(v));
		if (num.endsWith(".0")) num = num.substring(0, num.length() - 2);
		return num + units[u];
	}

	/**
	 * The first page of a video's comments as the comments card shows it: Top, or Newest, which is the page of the sort
	 * token the first page carries (no token, no Newest, as the card's sort row is then hidden).
	 */
	public static Page firstPage(String videoId, boolean newest) throws IOException {
		Page p = load(videoId);
		if (newest && !p.disabled && (p.sortNew != null)) p = more(p.sortNew);
		return p;
	}

	/**
	 * The batch {@code token} points to: the next batch of a list, a list sorted another way, or
	 * (see {@link Comment#repliesToken}) the replies to a comment.
	 */
	public static Page more(String token) throws IOException {
		return parse(call("next", body -> body.put("continuation", token)));
	}

	/** The video's comments section token: in its "comment-item-section" (else its comments panel). */
	@Nullable
	private static String commentsToken(JSONObject watch) {
		JSONObject section = findSection(watch);
		if (section != null) {
			String t = findString(section, "token");
			if (t != null) return t;
		}
		JSONObject panel = findPanel(watch);
		return (panel == null) ? null : findString(panel, "token");
	}

	@Nullable
	private static JSONObject findSection(Object node) {
		if (node instanceof JSONObject o) {
			JSONObject s = o.optJSONObject("itemSectionRenderer");
			if ((s != null) && "comment-item-section".equals(s.optString("sectionIdentifier"))) return s;
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				JSONObject r = findSection(o.opt(it.next()));
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				JSONObject r = findSection(a.opt(i));
				if (r != null) return r;
			}
		}
		return null;
	}

	@Nullable
	private static JSONObject findPanel(Object node) {
		if (node instanceof JSONObject o) {
			JSONObject p = o.optJSONObject("engagementPanelSectionListRenderer");
			if ((p != null) && "engagement-panel-comments-section".equals(p.optString("panelIdentifier"))) {
				return p;
			}
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				JSONObject r = findPanel(o.opt(it.next()));
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				JSONObject r = findPanel(a.opt(i));
				if (r != null) return r;
			}
		}
		return null;
	}

	/** A comment just posted, shown at once when YouTube's answer doesn't describe it. */
	public static Comment mine(String author, String text, boolean reply) {
		return new Comment("", author, null, text, "", "", false, false, false, reply, "", null, null);
	}

	// ---------------------------------------------------------------------------------------------
	// Posting

	/**
	 * Posts a new comment on the video; {@code createParams} is {@link Page#createParams}.
	 *
	 * @return the comment as YouTube now shows it, or null when it was posted but the answer didn't
	 * say how it looks
	 */
	@Nullable
	public static Comment post(String createParams, String text) throws IOException {
		JSONObject r = call("comment/create_comment", body -> {
			body.put("commentText", text);
			body.put("createCommentParams", createParams);
		});
		return firstPosted(r);
	}

	/** Posts a reply; {@code replyParams} is {@link Comment#replyParams}. */
	@Nullable
	public static Comment postReply(String replyParams, String text) throws IOException {
		JSONObject r = call("comment/create_comment_reply", body -> {
			body.put("commentText", text);
			body.put("createReplyParams", replyParams);
		});
		return firstPosted(r);
	}

	@Nullable
	private static Comment firstPosted(JSONObject r) {
		try {
			Page p = parse(r);
			return p.comments.isEmpty() ? null : p.comments.get(0);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The request

	private interface Body {
		void fill(JSONObject body) throws JSONException;
	}

	private static JSONObject call(String endpoint, Body fill) throws IOException {
		String cookies = cookies();
		boolean in = sapisid(cookies) != null;
		try {
			JSONObject client = new JSONObject();
			client.put("clientName", "WEB");
			client.put("clientVersion", CLIENT_VERSION);
			client.put("hl", "en");
			JSONObject ctx = new JSONObject();
			ctx.put("client", client);
			JSONObject body = new JSONObject();
			body.put("context", ctx);
			fill.fill(body);

			Map<String, String> h = new HashMap<>();
			h.put("X-YouTube-Client-Name", "1");
			h.put("X-YouTube-Client-Version", CLIENT_VERSION);
			h.put("Origin", ORIGIN);
			h.put("X-Origin", ORIGIN);
			h.put("Referer", ORIGIN + "/");
			String cookie = cookies;
			if (cookie.isEmpty() || !cookie.contains("SOCS=")) {
				cookie = (cookie.isEmpty() ? "" : cookie + "; ") + "SOCS=CAI";
			}
			h.put("Cookie", cookie);
			if (in) {
				String auth = authorization(cookies);
				if (auth != null) h.put("Authorization", auth);
				h.put("X-Goog-AuthUser", "0");
			}

			Http.Response r = Http.post(API + endpoint + "?prettyPrint=false", body.toString(), h);
			if (!r.isOk()) throw new IOException("YouTube returned HTTP " + r.code);
			JSONObject json = new JSONObject(r.body);
			JSONObject err = json.optJSONObject("error");
			if (err != null) throw new IOException("YouTube: " + err.optString("message", "error"));
			return json;
		} catch (JSONException ex) {
			throw new IOException("Unexpected YouTube response", ex);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Reading the answer

	private static Page parse(JSONObject root) {
		Page page = new Page();
		Map<String, JSONObject> entities = entities(root);
		List<JSONObject> items = new ArrayList<>();

		JSONArray ends = root.optJSONArray("onResponseReceivedEndpoints");
		for (int i = 0; (ends != null) && (i < ends.length()); i++) {
			JSONObject e = ends.optJSONObject(i);
			if (e == null) continue;
			JSONObject act = e.optJSONObject("appendContinuationItemsAction");
			if (act == null) act = e.optJSONObject("reloadContinuationItemsCommand");
			if (act == null) continue;
			JSONArray ci = act.optJSONArray("continuationItems");
			for (int j = 0; (ci != null) && (j < ci.length()); j++) {
				JSONObject it = ci.optJSONObject(j);
				if (it != null) items.add(it);
			}
		}

		// A freshly posted comment comes back as an action instead.
		JSONArray actions = root.optJSONArray("actions");
		for (int i = 0; (actions != null) && (i < actions.length()); i++) {
			JSONObject a = actions.optJSONObject(i);
			if (a == null) continue;
			for (String k : new String[]{"createCommentAction", "createCommentReplyAction"}) {
				JSONObject c = a.optJSONObject(k);
				JSONObject contents = (c == null) ? null : c.optJSONObject("contents");
				if (contents != null) items.add(contents);
			}
		}

		for (JSONObject item : items) {
			JSONObject header = item.optJSONObject("commentsHeaderRenderer");
			if (header != null) {
				readHeader(page, header);
				continue;
			}
			JSONObject thread = item.optJSONObject("commentThreadRenderer");
			if (thread != null) {
				Comment c = readThread(thread, entities);
				if (c != null) page.comments.add(c);
				continue;
			}
			JSONObject cont = item.optJSONObject("continuationItemRenderer");
			if (cont != null) {
				page.next = findString(cont, "token");
				continue;
			}
			// The items of a list of replies are comments themselves.
			Comment c = readComment(item, item, entities, true);
			if (c != null) page.comments.add(c);
		}
		return page;
	}

	/** Mutation key to payload: where the newer layout keeps what a comment says. */
	private static Map<String, JSONObject> entities(JSONObject root) {
		Map<String, JSONObject> m = new HashMap<>();
		JSONObject fu = root.optJSONObject("frameworkUpdates");
		JSONObject batch = (fu == null) ? null : fu.optJSONObject("entityBatchUpdate");
		JSONArray muts = (batch == null) ? null : batch.optJSONArray("mutations");
		for (int i = 0; (muts != null) && (i < muts.length()); i++) {
			JSONObject mu = muts.optJSONObject(i);
			if (mu == null) continue;
			JSONObject payload = mu.optJSONObject("payload");
			if (payload == null) continue;
			String key = mu.optString("entityKey");
			if (!key.isEmpty()) m.put(key, payload);
			JSONObject ce = payload.optJSONObject("commentEntityPayload");
			if ((ce != null) && !ce.optString("key").isEmpty()) m.put(ce.optString("key"), payload);
		}
		return m;
	}

	private static void readHeader(Page page, JSONObject header) {
		JSONObject count = header.optJSONObject("countText");
		if (count != null) {
			JSONArray runs = count.optJSONArray("runs");
			String c = (runs != null && runs.length() > 0) ? runs.optJSONObject(0).optString("text") :
					count.optString("simpleText");
			if (!c.isEmpty()) page.count = c.trim();
		}
		String create = findString(header, "createCommentParams");
		if (create != null) page.createParams = create;

		JSONObject menu = header.optJSONObject("sortMenu");
		JSONObject sub = (menu == null) ? null : menu.optJSONObject("sortFilterSubMenuRenderer");
		JSONArray subs = (sub == null) ? null : sub.optJSONArray("subMenuItems");
		for (int i = 0; (subs != null) && (i < subs.length()); i++) {
			JSONObject si = subs.optJSONObject(i);
			String token = (si == null) ? null : findString(si, "token");
			if (token == null) continue;
			// The two items are "Top comments" and "Newest first", in that order.
			if (i == 0) page.sortTop = token;
			else if (i == 1) page.sortNew = token;
		}
	}

	@Nullable
	private static Comment readThread(JSONObject thread, Map<String, JSONObject> entities) {
		Comment c = readComment(thread, thread, entities, false);
		return c;
	}

	/**
	 * A comment from either layout: the newer one points (by key) into the response's mutations, the
	 * older one holds everything in a {@code commentRenderer}.
	 *
	 * @param holder the thread (or reply item) the comment sits in, searched for its replies and for
	 *               the parameters a reply to it needs
	 */
	@Nullable
	private static Comment readComment(JSONObject node, JSONObject holder,
																		 Map<String, JSONObject> entities, boolean reply) {
		JSONObject vm = findObject(node, "commentKey");
		if (vm != null) return readNew(vm, holder, entities, reply);
		JSONObject r = (node.optJSONObject("comment") != null) ? node.optJSONObject("comment") : node;
		JSONObject cr = r.optJSONObject("commentRenderer");
		return (cr == null) ? null : readOld(cr, holder, reply);
	}

	@Nullable
	private static Comment readNew(JSONObject vm, JSONObject holder, Map<String, JSONObject> entities,
																 boolean reply) {
		JSONObject payload = entities.get(vm.optString("commentKey"));
		JSONObject e = (payload == null) ? null : payload.optJSONObject("commentEntityPayload");
		if (e == null) return null;
		JSONObject props = e.optJSONObject("properties");
		JSONObject author = e.optJSONObject("author");
		JSONObject toolbar = e.optJSONObject("toolbar");
		String id = (props == null) ? "" : props.optString("commentId");
		JSONObject content = (props == null) ? null : props.optJSONObject("content");
		String text = (content == null) ? "" : content.optString("content");
		String avatar = null;
		JSONObject av = e.optJSONObject("avatar");
		JSONObject img = (av == null) ? null : av.optJSONObject("image");
		JSONArray src = (img == null) ? null : img.optJSONArray("sources");
		if ((src != null) && (src.length() > 0)) avatar = src.optJSONObject(0).optString("url", null);
		if ((avatar == null) && (author != null)) avatar = author.optString("avatarThumbnailUrl", null);

		String repliesToken = null;
		JSONObject replies = holder.optJSONObject("replies");
		if (!reply && (replies != null)) repliesToken = findString(replies, "token");
		String replyCount = (toolbar == null) ? "" : toolbar.optString("replyCount");
		if (repliesToken == null) replyCount = "";

		String replyParams = findStringLike(holder, "replyparams");
		if (replyParams == null) {
			for (String k : new String[]{"toolbarSurfaceKey", "toolbarStateKey", "commentSurfaceKey"}) {
				JSONObject extra = entities.get(vm.optString(k));
				String p = (extra == null) ? null : findStringLike(extra, "replyparams");
				if (p != null) {
					replyParams = p;
					break;
				}
			}
		}

		if (replyParams == null) {
			// Any payload that mentions this comment may carry how to reply to it.
			for (JSONObject pl : entities.values()) {
				if ((pl == payload) || !pl.toString().contains(id)) continue;
				String p = findStringLike(pl, "replyparams");
				if (p != null) {
					replyParams = p;
					break;
				}
			}
		}

		return new Comment(id, (author == null) ? "" : author.optString("displayName"), avatar, text,
				(props == null) ? "" : props.optString("publishedTime"),
				(toolbar == null) ? "" : toolbar.optString("likeCountNotliked"),
				(author != null) && author.optBoolean("isCreator"),
				!vm.optString("pinnedText").isEmpty(),
				(toolbar != null) && !toolbar.optString("heartActiveTooltip").isEmpty(),
				reply, replyCount, repliesToken, replyParams);
	}

	private static Comment readOld(JSONObject cr, JSONObject holder, boolean reply) {
		String avatar = null;
		JSONObject at = cr.optJSONObject("authorThumbnail");
		JSONArray th = (at == null) ? null : at.optJSONArray("thumbnails");
		if ((th != null) && (th.length() > 0)) avatar = th.optJSONObject(th.length() - 1).optString("url", null);
		JSONObject replies = holder.optJSONObject("replies");
		String repliesToken = (!reply && (replies != null)) ? findString(replies, "token") : null;
		int rc = cr.optInt("replyCount", 0);
		return new Comment(cr.optString("commentId"), text(cr.optJSONObject("authorText")), avatar,
				text(cr.optJSONObject("contentText")), text(cr.optJSONObject("publishedTimeText")),
				text(cr.optJSONObject("voteCount")), cr.optBoolean("authorIsChannelOwner"),
				cr.has("pinnedCommentBadge"), cr.optJSONObject("actionButtons") != null &&
				findObject(cr.optJSONObject("actionButtons"), "creatorHeart") != null, reply,
				(repliesToken != null && rc > 0) ? String.valueOf(rc) : "", repliesToken,
				findStringLike(holder, "replyparams"));
	}

	/** {@code {"simpleText": ...}} or {@code {"runs": [{"text": ...}]}} as one string. */
	private static String text(@Nullable JSONObject o) {
		if (o == null) return "";
		String s = o.optString("simpleText");
		if (!s.isEmpty()) return s;
		JSONArray runs = o.optJSONArray("runs");
		if (runs == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < runs.length(); i++) {
			JSONObject r = runs.optJSONObject(i);
			if (r != null) sb.append(r.optString("text"));
		}
		return sb.toString();
	}

	/** The first string under {@code key}, anywhere below {@code node}. */
	@Nullable
	private static String findString(@Nullable Object node, String key) {
		if (node instanceof JSONObject o) {
			Object v = o.opt(key);
			if ((v instanceof String s) && !s.isEmpty()) return s;
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				String r = findString(o.opt(it.next()), key);
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				String r = findString(a.opt(i), key);
				if (r != null) return r;
			}
		}
		return null;
	}

	/** The first string under a key that ends with {@code suffix} (lower case), anywhere below. */
	@Nullable
	private static String findStringLike(@Nullable Object node, String suffix) {
		if (node instanceof JSONObject o) {
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				String k = it.next();
				Object v = o.opt(k);
				if ((v instanceof String str) && !str.isEmpty() && k.toLowerCase().endsWith(suffix)) {
					return str;
				}
			}
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				String r = findStringLike(o.opt(it.next()), suffix);
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				String r = findStringLike(a.opt(i), suffix);
				if (r != null) return r;
			}
		}
		return null;
	}

	/** The first object that has {@code key}, anywhere below (or at) {@code node}. */
	@Nullable
	private static JSONObject findObject(@Nullable Object node, String key) {
		if (node instanceof JSONObject o) {
			if (o.has(key)) return o;
			for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
				JSONObject r = findObject(o.opt(it.next()), key);
				if (r != null) return r;
			}
		} else if (node instanceof JSONArray a) {
			for (int i = 0; i < a.length(); i++) {
				JSONObject r = findObject(a.opt(i), key);
				if (r != null) return r;
			}
		}
		return null;
	}
}
