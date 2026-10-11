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
	 * @param background    what the switch sits on, so the off colours can be made opaque
	 */
	public static void apply(SwitchCompat s, int accent, int onAccent, int textSecondary, int background) {
		float d = s.getResources().getDisplayMetrics().density;
		int bg = ColorUtils.setAlphaComponent(background, 0xFF);
		int offTrack = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(textSecondary, 0x50), bg);
		int offThumb = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(textSecondary, 0xC0), offTrack);

		int w = Math.round(46 * d);
		int h = Math.round(26 * d);
		StateListDrawable track = new StateListDrawable();
		track.addState(new int[]{android.R.attr.state_checked}, pill(accent, w, h));
		track.addState(new int[]{}, pill(offTrack, w, h));

		// A disc a little smaller than the track is tall, so it sits inside it with an even gap.
		int gap = Math.round(4 * d);
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

	private static GradientDrawable pill(int color, int w, int h) {
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(h / 2f);
		g.setSize(w, h);
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
