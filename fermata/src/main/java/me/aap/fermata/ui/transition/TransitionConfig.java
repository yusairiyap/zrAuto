package me.aap.fermata.ui.transition;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * What the transition screen looks like and when it shows (Settings > Playback > Transition screen).
 * There are two sets, one for the phone and one for the car screen, each saved as one JSON text.
 * <p>
 * Positions and sizes are fractions of the screen (never pixels), so what is arranged in the settings
 * preview is what appears on any screen: {@link Element#x}/{@link Element#y} is an element's centre as
 * a fraction of the width/height, {@link Element#w} its width as a fraction of the width, and
 * {@link Element#size} a text's height as a fraction of the screen's height.
 * Element ids are saved, so never renumber the constants.
 */
public final class TransitionConfig {
	public static final Pref<Supplier<String>> PHONE = Pref.s("TRANSITION_PHONE", "");
	public static final Pref<Supplier<String>> CAR = Pref.s("TRANSITION_CAR", "");
	// Background modes.
	public static final int BG_TRANSPARENT = 0;
	public static final int BG_DIM = 1;
	public static final int BG_ART = 2;
	public static final int BG_PICTURE = 3;
	public static final int BG_GLASS = 4;

	// Where it shows.
	public static final int WHERE_ALL = 0;
	public static final int WHERE_FULLSCREEN = 1;
	public static final int WHERE_OUTSIDE = 2;

	// Animations (screen and elements).
	public static final int ANIM_NONE = 0;
	public static final int ANIM_FADE = 1;
	public static final int ANIM_UP = 2;
	public static final int ANIM_DOWN = 3;
	public static final int ANIM_LEFT = 4;
	public static final int ANIM_RIGHT = 5;
	public static final int ANIM_ZOOM_IN = 6;
	public static final int ANIM_ZOOM_OUT = 7;
	public static final int ANIM_COUNT = 8;

	// Element kinds.
	public static final int T_TITLE = 0;
	public static final int T_CHANNEL = 1;
	public static final int T_ART = 2;
	public static final int T_PLAYLIST = 3;
	public static final int T_TEXT = 4;

	// Fills.
	public static final int FILL_NONE = 0;
	public static final int FILL_SOLID = 1;
	public static final int FILL_GLASS = 2;

	public static final int ALIGN_LEFT = 0;
	public static final int ALIGN_CENTER = 1;
	public static final int ALIGN_RIGHT = 2;

	public static final String[] FONT_FAMILIES = {"sans-serif", "sans-serif-light", "sans-serif-condensed",
			"sans-serif-medium", "serif", "monospace", "cursive", "casual"};

	/** One thing on the screen. */
	public static final class Element {
		public int id;
		public int type;
		public boolean visible = true;
		public float x = 0.5f;
		public float y = 0.5f;
		public float w = 0.8f;
		public float size = 0.06f;
		public int font;
		public boolean bold;
		public boolean italic;
		public int align = ALIGN_CENTER;
		public int color = 0xFFFFFFFF;
		public int fill = FILL_NONE;
		public int fillColor = 0xFF000000;
		/** 0 to 100. */
		public int fillAlpha = 40;
		/** Corner radius in dp (art: 0 to 50 = a round cover at 50). */
		public int radius = 16;
		/** Space between the edge and the content, dp. */
		public int pad = 12;
		/** The edge line's width in dp (0 = none). */
		public int edge;
		public int edgeColor = 0x66FFFFFF;
		public int inAnim = ANIM_FADE;
		public int outAnim = ANIM_FADE;
		public int inMs = 500;
		public int outMs = 350;
		/** How long after the screen starts coming in this one starts, ms. */
		public int delay;
		/** Custom text. */
		public String text = "";

		public boolean isText() {
			return type != T_ART;
		}

		Element copy() {
			Element e = new Element();
			e.id = id;
			e.type = type;
			e.visible = visible;
			e.x = x;
			e.y = y;
			e.w = w;
			e.size = size;
			e.font = font;
			e.bold = bold;
			e.italic = italic;
			e.align = align;
			e.color = color;
			e.fill = fill;
			e.fillColor = fillColor;
			e.fillAlpha = fillAlpha;
			e.radius = radius;
			e.pad = pad;
			e.edge = edge;
			e.edgeColor = edgeColor;
			e.inAnim = inAnim;
			e.outAnim = outAnim;
			e.inMs = inMs;
			e.outMs = outMs;
			e.delay = delay;
			e.text = text;
			return e;
		}

		JSONObject toJson() throws JSONException {
			JSONObject o = new JSONObject();
			o.put("id", id).put("type", type).put("vis", visible).put("x", x).put("y", y).put("w", w)
					.put("size", size).put("font", font).put("bold", bold).put("italic", italic).put("align", align)
					.put("color", color).put("fill", fill).put("fillColor", fillColor).put("fillAlpha", fillAlpha)
					.put("radius", radius).put("pad", pad).put("edge", edge).put("edgeColor", edgeColor)
					.put("in", inAnim).put("out", outAnim).put("inMs", inMs).put("outMs", outMs).put("delay", delay)
					.put("text", text);
			return o;
		}

		static Element fromJson(JSONObject o) {
			Element e = new Element();
			e.id = o.optInt("id");
			e.type = o.optInt("type");
			e.visible = o.optBoolean("vis", true);
			e.x = (float) o.optDouble("x", 0.5);
			e.y = (float) o.optDouble("y", 0.5);
			e.w = (float) o.optDouble("w", 0.8);
			e.size = (float) o.optDouble("size", 0.06);
			e.font = o.optInt("font");
			e.bold = o.optBoolean("bold");
			e.italic = o.optBoolean("italic");
			e.align = o.optInt("align", ALIGN_CENTER);
			e.color = o.optInt("color", 0xFFFFFFFF);
			e.fill = o.optInt("fill");
			e.fillColor = o.optInt("fillColor", 0xFF000000);
			e.fillAlpha = o.optInt("fillAlpha", 40);
			e.radius = o.optInt("radius", 16);
			e.pad = o.optInt("pad", 12);
			e.edge = o.optInt("edge");
			e.edgeColor = o.optInt("edgeColor", 0x66FFFFFF);
			e.inAnim = o.optInt("in", ANIM_FADE);
			e.outAnim = o.optInt("out", ANIM_FADE);
			e.inMs = o.optInt("inMs", 500);
			e.outMs = o.optInt("outMs", 350);
			e.delay = o.optInt("delay");
			e.text = o.optString("text", "");
			e.clamp();
			return e;
		}

		public void clamp() {
			x = Math.max(0f, Math.min(1f, x));
			y = Math.max(0f, Math.min(1f, y));
			w = Math.max(0.06f, Math.min(1f, w));
			size = Math.max(0.015f, Math.min(0.25f, size));
			font = Math.max(0, Math.min(FONT_FAMILIES.length - 1, font));
			fillAlpha = Math.max(0, Math.min(100, fillAlpha));
			radius = Math.max(0, Math.min(50, radius));
			pad = Math.max(0, Math.min(48, pad));
			edge = Math.max(0, Math.min(8, edge));
			inMs = Math.max(0, Math.min(3000, inMs));
			outMs = Math.max(0, Math.min(3000, outMs));
			delay = Math.max(0, Math.min(5000, delay));
			inAnim = Math.max(0, Math.min(ANIM_COUNT - 1, inAnim));
			outAnim = Math.max(0, Math.min(ANIM_COUNT - 1, outAnim));
		}
	}

	public boolean on;
	public int bg = BG_ART;
	/** How dark the background is (the dim for the colour, the shade over a picture), 0 to 100. */
	public int dim = 45;
	/** How soft the cover/picture is, 0 to 100. */
	public int blur = 55;
	/** How long it stays, in tenths of a second. */
	public int holdTenths = 40;
	public int inAnim = ANIM_FADE;
	public int outAnim = ANIM_FADE;
	public int inMs = 450;
	public int outMs = 450;
	public int where = WHERE_ALL;
	public boolean tapToDismiss = true;
	/** Whether the first song after the app starts also gets it (else only a song after another). */
	public boolean firstSong;
	public final List<Element> elements = new ArrayList<>();
	private int nextId = 1;

	private TransitionConfig() {
	}

	// ---- Defaults ----

	@NonNull
	public static TransitionConfig defaults(boolean car) {
		TransitionConfig c = new TransitionConfig();
		c.on = false;
		c.holdTenths = car ? 45 : 40;
		Element art = c.add(T_ART);
		art.x = 0.5f;
		art.y = car ? 0.45f : 0.30f;
		art.w = car ? 0.20f : 0.34f;
		art.radius = 18;
		art.inAnim = ANIM_ZOOM_IN;
		Element title = c.add(T_TITLE);
		title.x = car ? 0.5f : 0.5f;
		title.y = car ? 0.80f : 0.60f;
		title.size = car ? 0.075f : 0.045f;
		title.bold = true;
		title.w = 0.86f;
		title.inAnim = ANIM_UP;
		title.delay = 120;
		Element ch = c.add(T_CHANNEL);
		ch.x = 0.5f;
		ch.y = car ? 0.90f : 0.68f;
		ch.size = car ? 0.05f : 0.03f;
		ch.color = 0xCCFFFFFF;
		ch.w = 0.86f;
		ch.inAnim = ANIM_UP;
		ch.delay = 240;
		Element pl = c.add(T_PLAYLIST);
		pl.visible = false;
		pl.x = 0.5f;
		pl.y = car ? 0.12f : 0.80f;
		pl.size = car ? 0.045f : 0.026f;
		pl.fill = FILL_GLASS;
		pl.radius = 24;
		pl.pad = 14;
		pl.color = 0xFFFFFFFF;
		pl.w = 0.6f;
		pl.delay = 360;
		return c;
	}

	/** Adds a new element of {@code type} in the middle with sensible looks, and returns it. */
	@NonNull
	public Element add(int type) {
		Element e = new Element();
		e.id = nextId++;
		e.type = type;
		e.size = (type == T_TITLE) ? 0.06f : 0.035f;
		e.w = (type == T_ART) ? 0.3f : 0.8f;
		if (type == T_ART) e.radius = 16;
		elements.add(e);
		return e;
	}

	public void remove(Element e) {
		elements.remove(e);
	}

	@Nullable
	public Element find(int id) {
		for (Element e : elements) if (e.id == id) return e;
		return null;
	}

	// ---- Saving ----

	private static Pref<Supplier<String>> pref(boolean car) {
		return car ? CAR : PHONE;
	}

	public static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}

	/** The saved set, or the defaults. */
	@NonNull
	public static TransitionConfig load(boolean car) {
		String s = store().getStringPref(pref(car));
		if ((s != null) && !s.isEmpty()) {
			try {
				return fromJson(new JSONObject(s));
			} catch (JSONException ignore) {
				// Unreadable: the defaults.
			}
		}
		return defaults(car);
	}

	public void save(boolean car) {
		try {
			store().applyStringPref(pref(car), toJson().toString());
		} catch (JSONException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to save the transition screen");
		}
	}

	public static void reset(boolean car) {
		store().removePref(pref(car));
	}

	JSONObject toJson() throws JSONException {
		JSONObject o = new JSONObject();
		o.put("on", on).put("bg", bg).put("dim", dim).put("blur", blur).put("hold", holdTenths)
				.put("in", inAnim).put("out", outAnim).put("inMs", inMs).put("outMs", outMs)
				.put("where", where).put("tap", tapToDismiss).put("first", firstSong).put("next", nextId);
		JSONArray a = new JSONArray();
		for (Element e : elements) a.put(e.toJson());
		o.put("els", a);
		return o;
	}

	static TransitionConfig fromJson(JSONObject o) throws JSONException {
		TransitionConfig c = new TransitionConfig();
		c.on = o.optBoolean("on");
		c.bg = o.optInt("bg", BG_ART);
		c.dim = o.optInt("dim", 45);
		c.blur = o.optInt("blur", 55);
		c.holdTenths = Math.max(10, Math.min(300, o.optInt("hold", 40)));
		c.inAnim = o.optInt("in", ANIM_FADE);
		c.outAnim = o.optInt("out", ANIM_FADE);
		c.inMs = Math.max(0, Math.min(3000, o.optInt("inMs", 450)));
		c.outMs = Math.max(0, Math.min(3000, o.optInt("outMs", 450)));
		c.where = o.optInt("where");
		c.tapToDismiss = o.optBoolean("tap", true);
		c.firstSong = o.optBoolean("first");
		c.nextId = Math.max(1, o.optInt("next", 1));
		JSONArray a = o.optJSONArray("els");
		if (a != null) {
			for (int i = 0; i < a.length(); i++) {
				Element e = Element.fromJson(a.getJSONObject(i));
				c.elements.add(e);
				c.nextId = Math.max(c.nextId, e.id + 1);
			}
		}
		return c;
	}

	/** A copy to edit without touching the original. */
	@NonNull
	public TransitionConfig copy() {
		try {
			return fromJson(toJson());
		} catch (JSONException ex) {
			return defaults(false);
		}
	}

	/** Which element kind wants which default name in the settings. */
	public static int nameRes(int type) {
		return switch (type) {
			case T_TITLE -> me.aap.fermata.R.string.tr_el_title;
			case T_CHANNEL -> me.aap.fermata.R.string.tr_el_channel;
			case T_ART -> me.aap.fermata.R.string.tr_el_art;
			case T_PLAYLIST -> me.aap.fermata.R.string.tr_el_playlist;
			default -> me.aap.fermata.R.string.tr_el_text;
		};
	}
}
