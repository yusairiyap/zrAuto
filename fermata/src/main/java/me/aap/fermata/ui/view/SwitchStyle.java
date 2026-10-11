package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;

import me.aap.fermata.R;

/**
 * The look of the switches the app builds in code (the settings pages): on, a track in the accent
 * colour with a thumb that reads on it; off, a dimmed track and thumb, nothing in the accent. Both
 * are opaque and drawn as one pill and one disc, so the thumb never blends into the track the way the
 * platform's semi-transparent tinted default did.
 */
public final class SwitchStyle {
	private SwitchStyle() {
	}

	/** Styles {@code s} from the music palette of {@code palette} (see {@link EffectsUi#palette}). */
	public static void apply(SwitchCompat s, Context palette) {
		int accent = EffectsUi.accent(palette);
		int secondary = color(palette, R.attr.musicTextSecondary);
		int fill = color(palette, R.attr.musicChipFill);
		apply(s, accent, EffectsUi.onAccent(accent), secondary, fill);
	}

	/**
	 * @param textSecondary the dim colour the off state is made of
	 * @param background    unused now (kept for callers): the off state no longer depends on it
	 */
	public static void apply(SwitchCompat s, int accent, int onAccent, int textSecondary, int background) {
		float d = s.getResources().getDisplayMetrics().density;
		// Off is made of the dim text colour itself, a translucent track and an opaque
		// thumb, so it reads on any card. (Not blended onto a background colour: the chip fills the
		// switches sit on are translucent, and forcing them opaque turned the whole switch white.)
		// On: the accent, a little lighter than the theme's (a dark accent made a dark, heavy switch,
		// most of all on the dark themes), and never so dark it sinks into the page. The thumb is
		// white or near-black, whichever reads better on that track.
		int onTrack = lighten(accent);
		int white = 0xFFFFFFFF;
		int dark = 0xFF1C1B1F;
		int onThumb = (ColorUtils.calculateContrast(white, onTrack) >= ColorUtils.calculateContrast(dark, onTrack)) ?
				white : dark;
		// Off, one look for every switch: a dimmed track and a solid gray thumb, both the theme's own dim
		// text colour (translucent over whatever the switch sits on, so no background is assumed).
		int offTrack = ColorUtils.setAlphaComponent(textSecondary, 0x50);
		int offThumb = ColorUtils.setAlphaComponent(textSecondary, 0xFF);

		int w = Math.round(46 * d);
		int h = Math.round(26 * d);
		StateListDrawable track = new StateListDrawable();
		track.addState(new int[]{android.R.attr.state_checked}, pill(onTrack, 0, 0, w, h, d));
		track.addState(new int[]{}, pill(offTrack, 0, 0, w, h, d));

		// A disc a little smaller than the track is tall, so it sits inside it with an even gap.
		int gap = Math.round(5 * d);
		int disc = h - 2 * gap;
		StateListDrawable thumb = new StateListDrawable();
		thumb.addState(new int[]{android.R.attr.state_checked},
				new InsetDrawable(disc(onThumb, disc), gap));
		thumb.addState(new int[]{}, new InsetDrawable(disc(offThumb, disc), gap));

		s.setTrackDrawable(track);
		s.setThumbDrawable(thumb);
		s.setSwitchMinWidth(w);
		s.setThumbTintList(null);
		s.setTrackTintList(null);
	}

	/** The accent made lighter: a touch always (unless already light), more while it is still dark. */
	private static int lighten(int color) {
		int white = 0xFFFFFFFF;
		int c = color | 0xFF000000;
		if (ColorUtils.calculateLuminance(c) < 0.7) c = ColorUtils.blendARGB(c, white, 0.18f);
		for (int i = 0; (i < 4) && (ColorUtils.calculateLuminance(c) < 0.2); i++) {
			c = ColorUtils.blendARGB(c, white, 0.25f);
		}
		return c;
	}

	private static GradientDrawable pill(int color, int stroke, int strokeDp, int w, int h, float d) {
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(h / 2f);
		g.setSize(w, h);
		if (strokeDp > 0) g.setStroke(Math.round(strokeDp * d), stroke);
		return g;
	}

	private static GradientDrawable disc(int color, int size) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.OVAL);
		g.setColor(color);
		g.setSize(size, size);
		return g;
	}

	private static int color(Context ctx, @AttrRes int attr) {
		TypedValue tv = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}
}
