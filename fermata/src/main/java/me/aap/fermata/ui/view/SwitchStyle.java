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
		// Off is made of the dim text colour itself, a translucent track with an outline and an opaque
		// thumb, so it reads on any card. (Not blended onto a background colour: the chip fills the
		// switches sit on are translucent, and forcing them opaque turned the whole switch white.)
		int offTrack = ColorUtils.setAlphaComponent(textSecondary, 0x40);
		int offStroke = ColorUtils.setAlphaComponent(textSecondary, 0xA0);
		int offThumb = ColorUtils.setAlphaComponent(textSecondary, 0xFF);

		int w = Math.round(46 * d);
		int h = Math.round(26 * d);
		StateListDrawable track = new StateListDrawable();
		track.addState(new int[]{android.R.attr.state_checked}, pill(accent, 0, 0, w, h, d));
		track.addState(new int[]{}, pill(offTrack, offStroke, 2, w, h, d));

		// A disc a little smaller than the track is tall, so it sits inside it with an even gap.
		int gap = Math.round(5 * d);
		int disc = h - 2 * gap;
		StateListDrawable thumb = new StateListDrawable();
		thumb.addState(new int[]{android.R.attr.state_checked},
				new InsetDrawable(disc(onAccent, disc), gap));
		thumb.addState(new int[]{}, new InsetDrawable(disc(offThumb, disc), gap));

		s.setTrackDrawable(track);
		s.setThumbDrawable(thumb);
		s.setSwitchMinWidth(w);
		s.setThumbTintList(null);
		s.setTrackTintList(null);
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
