package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import me.aap.fermata.R;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;

/**
 * Bits the effects screens (the native one and YouTube's) share: they are drawn in the Music tab's
 * palette, like the Music and Data Usage tabs, and their slider rows all work the same way.
 */
public final class EffectsUi {
	private EffectsUi() {
	}

	/** {@code ctx} with the Music tab's palette (dark or light, to match the app theme) on top. */
	public static Context palette(Context ctx) {
		return new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
	}

	/**
	 * The theme's own accent color (the one the app's sliders and switches are drawn in), so the
	 * effects screens and the more menu follow whichever theme is picked.
	 */
	public static int accent(Context ctx) {
		TypedValue tv = new TypedValue();
		try {
			if (ctx.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorControlActivated, tv, true)) {
				if ((tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= TypedValue.TYPE_LAST_COLOR_INT)) {
					return tv.data;
				}
				if (tv.resourceId != 0) return ContextCompat.getColor(ctx, tv.resourceId);
			}
		} catch (RuntimeException ignore) {
			// Falls through to the default below.
		}
		return ContextCompat.getColor(ctx, R.color.music_accent);
	}

	/** Black or white, whichever reads on {@code accent}. */
	public static int onAccent(int accent) {
		return (ColorUtils.calculateLuminance(accent) > 0.5) ? 0xFF000000 : 0xFFFFFFFF;
	}

	public static LayoutInflater inflater(Context ctx) {
		return LayoutInflater.from(palette(ctx));
	}

	/** A slider row (equalizer_channel.xml) that tells {@code onUser} about changes the user makes. */
	public static final class Row {
		public final View view;
		public final TextView label;
		public final TextView value;
		public final StepSeekBar seek;
		private final IntFunction<String> format;

		private Row(View view, IntFunction<String> format) {
			this.view = view;
			this.format = format;
			label = view.findViewById(R.id.eq_channel_label);
			value = view.findViewById(R.id.eq_channel_value);
			seek = view.findViewById(R.id.eq_channel_seek);
		}

		/** Greys the row out and makes it ignore touches while its feature is off. */
		public void setEnabled(boolean enabled) {
			view.setAlpha(enabled ? 1f : 0.4f);
			seek.setEnabled(enabled);
			View minus = view.findViewById(R.id.eq_channel_minus);
			View plus = view.findViewById(R.id.eq_channel_plus);
			if (minus != null) minus.setEnabled(enabled);
			if (plus != null) plus.setEnabled(enabled);
		}

		/** Moves the slider without telling the listener. */
		public void set(int progress) {
			seek.setProgress(progress);
			value.setText(format.apply(progress));
		}
	}

	/**
	 * Adds a slider row to {@code parent}: 0..{@code max}, moving {@code step} for each press of its
	 * buttons, its value shown by {@code format}; {@code onUser} is called with the progress for every
	 * change the user makes.
	 */
	public static Row addRow(LayoutInflater inflater, ViewGroup parent, @StringRes int label, int max,
													 int step, IntFunction<String> format, IntConsumer onUser) {
		View v = inflater.inflate(R.layout.equalizer_channel, parent, false);
		parent.addView(v);
		Row row = new Row(v, format);
		row.label.setText(label);
		row.seek.setMax(max);
		row.seek.setStep(step);
		row.seek.bindButtons(v.findViewById(R.id.eq_channel_minus), v.findViewById(R.id.eq_channel_plus));
		row.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
				row.value.setText(format.apply(progress));
				if (fromUser) onUser.accept(progress);
			}

			@Override
			public void onStartTrackingTouch(SeekBar sb) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar sb) {
			}
		});
		return row;
	}
}
