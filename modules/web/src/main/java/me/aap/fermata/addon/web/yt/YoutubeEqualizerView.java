package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.SwitchCompat;

import java.util.List;
import java.util.Locale;

import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.function.IntConsumer;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.pref.PreferenceView;
import me.aap.utils.pref.PreferenceView.ListOpts;

/**
 * The YouTube-tab Equalizer/Bass Boost/Virtualizer/Live Hall reverb panel, shown full-screen
 * (via a {@link me.aap.utils.ui.fragment.GenericFragment}) from the video menu. Reuses the native
 * {@code audio_effects.xml}/{@code equalizer_channel.xml} layouts (this module already depends on
 * {@code :fermata}, and both native and YouTube equalizer screens share the same mixer-strip
 * design), but binds them to {@link YoutubeAddon}'s Web-Audio-backed prefs instead of an
 * {@link android.media.audiofx.AudioEffects} instance, since YouTube plays through the WebView's
 * own audio pipeline rather than one of the app's native engines. Unlike the native panel,
 * settings here are global to the YouTube tab (no per-track/per-folder scope, no Volume Boost),
 * so those sections/controls of the shared layout are hidden -- except the Virtualizer-mode
 * dropdown, which has no equivalent here but is repurposed (not hidden) for Live Hall's reverb
 * engine/quality selector, since it's otherwise the one unused row in this shared layout.
 */
final class YoutubeEqualizerView extends android.widget.ScrollView implements PreferenceStore.Listener {
	@Nullable
	private YoutubeAddon addon;
	@Nullable
	private YoutubeWebView web;

	public YoutubeEqualizerView(Context context) {
		this(context, null);
		setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
	}

	public YoutubeEqualizerView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setBackgroundColor(Color.TRANSPARENT);
	}

	void init(YoutubeWebView web) {
		this.web = web;
		YoutubeAddon addon = this.addon = web.getAddon();
		inflate(getContext(), me.aap.fermata.R.layout.audio_effects, this);
		// virtualizer_mode is the native Audio effects screen's Virtualizer-mode dropdown -- unused
		// here (YouTube's Web-Audio virtualizer has no such mode selector), so it's repurposed rather
		// than hidden: it's the one existing row in this shared layout with no other job on this
		// screen, and reusing it avoids any layout/UI change for a single "Hall quality" setting.
		hide(me.aap.fermata.R.id.apply_to,
				me.aap.fermata.R.id.equalizer_preset_save, me.aap.fermata.R.id.equalizer_preset_delete);
		addon.getPreferenceStore().addBroadcastListener(this);
		// GenericFragment is a single instance shared by every "generic screen" caller in the app
		// (e.g. the About screen) -- there's no per-open "closed" callback like OverlayMenu had, so
		// clean up whenever this view actually leaves the window instead (reliably triggered by
		// GenericFragment.setContentProvider()'s removeAllViews() on the next re-entrant use, or by
		// the fragment/activity being torn down).
		addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(View v) {
			}

			@Override
			public void onViewDetachedFromWindow(View v) {
				removeOnAttachStateChangeListener(this);
				cleanup();
			}
		});

		SwitchCompat eqSwitch = findViewById(me.aap.fermata.R.id.equalizer_switch);
		eqSwitch.setChecked(addon.eqEnabled());
		eqSwitch.setOnCheckedChangeListener((b, checked) -> {
			addon.setEqEnabled(checked);
			push();
		});

		String[] names = new String[YoutubeEqualizerPresets.PRESET_NAMES.length + 1];
		names[0] = getResources().getString(me.aap.fermata.R.string.eq_manual);
		for (int i = 0; i < YoutubeEqualizerPresets.PRESET_NAMES.length; i++) {
			names[i + 1] = getResources().getString(YoutubeEqualizerPresets.PRESET_NAMES[i]);
		}

		PreferenceView presetView = findViewById(me.aap.fermata.R.id.equalizer_preset);
		presetView.setPreference(null, () -> {
			ListOpts o = new ListOpts();
			o.store = addon.getPreferenceStore();
			o.pref = YoutubeAddon.YT_EQ_PRESET;
			o.title = me.aap.fermata.R.string.string_format;
			o.formatTitle = true;
			o.stringValues = names;
			return o;
		});

		// Live Hall's reverb engine: Smooth (cheap algorithmic comb/allpass, default) trades some
		// character for far lower CPU cost than Rich (the original impulse-response convolution) --
		// see youtube_equalizer.js. Only affects Live Hall's sound while it's enabled below; doesn't
		// need its own on/off switch.
		PreferenceView hallQualityView = findViewById(me.aap.fermata.R.id.virtualizer_mode);
		hallQualityView.setPreference(null, () -> {
			ListOpts o = new ListOpts();
			o.store = addon.getPreferenceStore();
			o.pref = YoutubeAddon.YT_REVERB_ENGINE;
			o.title = me.aap.fermata.R.string.hall_quality;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{me.aap.fermata.R.string.hall_quality_smooth,
					me.aap.fermata.R.string.hall_quality_rich};
			o.valuesMap = new int[]{0, 1};
			return o;
		});

		createChannels(addon);
	}

	void cleanup() {
		if (addon != null) addon.getPreferenceStore().removeBroadcastListener(this);
		removeAllViews();
		addon = null;
		web = null;
	}

	private void createChannels(YoutubeAddon addon) {
		LinearLayout channels = findViewById(me.aap.fermata.R.id.equalizer_channels);
		LinearLayout effects = findViewById(me.aap.fermata.R.id.equalizer_effects);
		LayoutInflater inflater = LayoutInflater.from(getContext());
		int[] bands = addon.eqBands();
		int range = YoutubeEqualizerPresets.BAND_MAX - YoutubeEqualizerPresets.BAND_MIN;

		for (int i = 0; i < YoutubeEqualizerPresets.NUM_BANDS; i++) {
			View ch = inflater.inflate(me.aap.fermata.R.layout.equalizer_channel, channels, false);
			channels.addView(ch);
			bindBandChannel(ch, i, bands, range);
		}

		addEffectChannel(inflater, effects, me.aap.fermata.R.string.bass_boost, addon.bassEnabled(),
				addon.bassStrength(), 1000, addon::setBassEnabled, addon::setBassStrength);
		addEffectChannel(inflater, effects, me.aap.fermata.R.string.virtualizer, addon.virtEnabled(),
				addon.virtStrength(), 1000, addon::setVirtEnabled, addon::setVirtStrength);
		addEffectChannel(inflater, effects, me.aap.fermata.R.string.live_hall, addon.reverbEnabled(),
				addon.reverbStrength(), 1500, addon::setReverbEnabled, addon::setReverbStrength);
		// Reverb impulse-response length: no on/off of its own (only matters while Live Hall itself
		// is enabled, above) but its own fader since it directly trades sound quality for CPU cost --
		// unlike strength, going lower here actually reduces Live Hall's processing cost, not just
		// its volume. Range and default (300-3000ms, 2500ms) mirror YoutubeAddon's YT_REVERB_DURATION.
		addDurationChannel(inflater, effects, me.aap.fermata.R.string.reverb_duration,
				addon.reverbDuration(), 300, 3000, addon::setReverbDuration);
	}

	private void bindBandChannel(View ch, int band, int[] bands, int range) {
		TextView value = ch.findViewById(me.aap.fermata.R.id.eq_channel_value);
		TextView label = ch.findViewById(me.aap.fermata.R.id.eq_channel_label);
		AppCompatSeekBar sb = ch.findViewById(me.aap.fermata.R.id.eq_channel_seek);

		value.setText(formatDb(bands[band]));
		sb.setMax(range);
		sb.setProgress(bands[band] - YoutubeEqualizerPresets.BAND_MIN);
		sb.setOnSeekBarChangeListener(new SeekBarListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				short level = (short) (progress + YoutubeEqualizerPresets.BAND_MIN);
				value.setText(formatDb(level));
				if (fromUser) bandChanged(band, level);
			}
		});

		float freq = YoutubeEqualizerPresets.CENTER_FREQ_HZ[band];
		if (freq >= 1000) {
			freq /= 1000;
			String s = String.format((freq == Math.floor(freq)) ? "%.0f" : "%.1f", freq);
			label.setText(getResources().getString(me.aap.fermata.R.string.eq_khz, s));
		} else {
			label.setText(getResources().getString(me.aap.fermata.R.string.eq_hz,
					String.valueOf((int) freq)));
		}
	}

	private void addEffectChannel(LayoutInflater inflater, ViewGroup parent, @StringRes int labelRes,
																 boolean enabled, int strength, int max, BooleanConsumer onEnabledChanged,
																 IntConsumer onStrengthChanged) {
		View ch = inflater.inflate(me.aap.fermata.R.layout.equalizer_channel, parent, false);
		parent.addView(ch);

		SwitchCompat sw = ch.findViewById(me.aap.fermata.R.id.eq_channel_switch);
		TextView value = ch.findViewById(me.aap.fermata.R.id.eq_channel_value);
		TextView label = ch.findViewById(me.aap.fermata.R.id.eq_channel_label);
		AppCompatSeekBar sb = ch.findViewById(me.aap.fermata.R.id.eq_channel_seek);

		sw.setVisibility(VISIBLE);
		sw.setChecked(enabled);
		sw.setOnCheckedChangeListener((b, checked) -> {
			onEnabledChanged.accept(checked);
			push();
		});

		label.setText(labelRes);
		value.setText(formatPercent(strength));
		sb.setMax(max);
		sb.setProgress(strength);
		sb.setOnSeekBarChangeListener(new SeekBarListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				value.setText(formatPercent(progress));
				if (fromUser) {
					onStrengthChanged.accept(progress);
					push();
				}
			}
		});
	}

	private void addDurationChannel(LayoutInflater inflater, ViewGroup parent, @StringRes int labelRes,
																	 int durationMs, int minMs, int maxMs, IntConsumer onDurationChanged) {
		View ch = inflater.inflate(me.aap.fermata.R.layout.equalizer_channel, parent, false);
		parent.addView(ch);

		// This channel has no switch of its own (Hall Size only matters while Live Hall, the channel
		// to its left, is on) but INVISIBLE rather than the layout's default GONE reserves the same
		// vertical space the other channels' switches take, so this fader lines up at the same height
		// as theirs instead of sitting higher.
		View sw = ch.findViewById(me.aap.fermata.R.id.eq_channel_switch);
		sw.setVisibility(INVISIBLE);

		TextView value = ch.findViewById(me.aap.fermata.R.id.eq_channel_value);
		TextView label = ch.findViewById(me.aap.fermata.R.id.eq_channel_label);
		AppCompatSeekBar sb = ch.findViewById(me.aap.fermata.R.id.eq_channel_seek);

		label.setText(labelRes);
		value.setText(formatSeconds(durationMs));
		sb.setMax(maxMs - minMs);
		sb.setProgress(durationMs - minMs);
		sb.setOnSeekBarChangeListener(new SeekBarListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				value.setText(formatSeconds(progress + minMs));
			}

			// Unlike every other channel here, committing this one re-synthesizes a multi-second
			// impulse response -- real, non-trivial CPU work, not just a gain update -- so only do it
			// once the user releases the slider, not on every pixel of drag.
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				onDurationChanged.accept(seekBar.getProgress() + minMs);
				push();
			}
		});
	}

	private void bandChanged(int band, short level) {
		YoutubeAddon addon = this.addon;
		if (addon == null) return;
		int[] bands = addon.eqBands();
		bands[band] = level;
		addon.setEqBands(bands);
		if (addon.eqPreset() != 0) addon.setEqPreset(0);
		push();
	}

	private void setBandValues(int[] bands) {
		LinearLayout channels = findViewById(me.aap.fermata.R.id.equalizer_channels);
		for (int i = 0; i < YoutubeEqualizerPresets.NUM_BANDS; i++) {
			View ch = channels.getChildAt(i);
			AppCompatSeekBar sb = ch.findViewById(me.aap.fermata.R.id.eq_channel_seek);
			TextView value = ch.findViewById(me.aap.fermata.R.id.eq_channel_value);
			sb.setProgress(bands[i] - YoutubeEqualizerPresets.BAND_MIN);
			value.setText(formatDb(bands[i]));
		}
	}

	private static String formatDb(int centibels) {
		return String.format(Locale.ROOT, "%+d", Math.round(centibels / 100f));
	}

	private static String formatPercent(int progress) {
		return (progress / 10) + "%";
	}

	private static String formatSeconds(int ms) {
		return String.format(Locale.ROOT, "%.1fs", ms / 1000f);
	}

	private void push() {
		if (web != null) web.configureEqualizer();
	}

	private void hide(@IdRes int... ids) {
		for (int id : ids) {
			View v = findViewById(id);
			if (v != null) v.setVisibility(GONE);
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		YoutubeAddon addon = this.addon;
		if ((addon == null) || !prefs.contains(YoutubeAddon.YT_EQ_PRESET)) return;

		int preset = addon.eqPreset();
		if (preset == 0) return;

		int idx = preset - 1;
		if ((idx < 0) || (idx >= YoutubeEqualizerPresets.PRESETS.length)) return;

		int[] bands = YoutubeEqualizerPresets.PRESETS[idx].clone();
		addon.setEqBands(bands);
		setBandValues(bands);
		push();
	}

	private static abstract class SeekBarListener implements SeekBar.OnSeekBarChangeListener {
		public void onStartTrackingTouch(SeekBar seekBar) {
		}

		public void onStopTrackingTouch(SeekBar seekBar) {
		}
	}
}
