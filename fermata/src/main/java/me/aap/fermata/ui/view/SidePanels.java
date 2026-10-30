package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.EffectsControls;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ytcaptions.CaptionsHub;

/**
 * The audio effects and the captions settings as {@link SidePanel}s, for use without leaving fullscreen video. Outside
 * fullscreen video, or with the panel switched off in Settings, the same actions open the full pages as before.
 */
public final class SidePanels {
	private SidePanels() {
	}

	// ---------------------------------------------------------------------------------------------
	// Audio effects

	public static void effects(MainActivityDelegate a) {
		boolean panel = SidePanel.isVideoUp(a) && a.getPrefs().getBooleanPref(MainActivityPrefs.SIDE_PANEL_EFFECTS);
		if (!panel) {
			AllInOneMenu.openEffects(a);
			return;
		}
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem src = (eng == null) ? null : eng.getSource();
		// The same screen as the full page (equalizer, bass, virtualizer, the 3D sound stage, presets in a menu), in the panel.
		if ((src != null) && (eng.supportsAudioEffects() || eng.supportsSoundStage())) {
			SidePanel.toggle(a, "effects", R.drawable.equalizer, R.string.action_audio_effects, MainActivityPrefs.SIDE_PANEL_EFFECTS_PERCENT, new SidePanel.Body() {
				private AudioEffectsView view;

				@Override
				public View create(SidePanel p) {
					view = new AudioEffectsView(a.getContext());
					MediaSessionCallback cb = a.getMediaServiceBinder().getMediaSessionCallback();
					view.init(cb, eng.supportsAudioEffects() ? eng.ensureAudioEffects() : null, src);
					tidyEffects(view);
					return view;
				}

				@Override
				public void onClosed() {
					if (view == null) return;
					view.apply(a.getMediaServiceBinder().getMediaSessionCallback());
					view.cleanup();
				}
			});
			return;
		}
		// YouTube's page plays through Web Audio: its own effects screen (the same layout), in the panel.
		View yt = (eng != null) ? ((eng.getId() == MediaPrefs.MEDIA_ENG_YT) ? MusicPlayer.youtubeEffectsView(a) : null) :
				MusicPlayer.youtubeEffectsView(a);
		if (yt != null) {
			SidePanel.toggle(a, "effects", R.drawable.equalizer, R.string.action_audio_effects, MainActivityPrefs.SIDE_PANEL_EFFECTS_PERCENT, p -> {
				tidyEffects(yt);
				return yt;
			});
			return;
		}
		if (effectsOf(a) == null) {
			TopToast.show(R.drawable.equalizer, R.string.music_effects_unavailable);
			return;
		}
		SidePanel.toggle(a, "effects", R.drawable.equalizer, R.string.action_audio_effects, MainActivityPrefs.SIDE_PANEL_EFFECTS_PERCENT, p -> effectsBody(p));
	}

	/**
	 * The effects screen made for a whole page sits under the bars (a big gap on top) and offers where to keep the settings:
	 * in a panel next to the video the gap is closed and the "Apply to" part left out.
	 */
	private static void tidyEffects(View v) {
		View apply = v.findViewById(R.id.apply_to);
		if (apply != null) apply.setVisibility(View.GONE);
		int pad = Math.round(6 * v.getResources().getDisplayMetrics().density);
		Runnable fix = () -> {
			if ((v.getPaddingTop() != pad) || (v.getPaddingBottom() != pad)) v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
		};
		fix.run();
		// The bars' insets are put back on every layout: put away again.
		v.addOnLayoutChangeListener((x, l, t, r, b, ol, ot, or, ob) -> fix.run());
	}

	/** The effects of what plays; YouTube's own for its page, none for a player without any. */
	@Nullable
	private static EffectsControls effectsOf(MainActivityDelegate a) {
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		if (eng == null) return MusicPlayer.getYoutubeEffects();
		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) return MusicPlayer.getYoutubeEffects();
		if (!eng.supportsAudioEffects()) return null;
		try {
			return EffectsControls.of(eng.ensureAudioEffects());
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static View effectsBody(SidePanel p) {
		ScrollView sv = new ScrollView(p.ctx);
		LinearLayout col = new LinearLayout(p.ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		sv.addView(col, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		EffectsControls fx = effectsOf(p.activity());
		if (fx == null) return sv;
		int[] kinds = {EffectsControls.EQ, EffectsControls.BASS, EffectsControls.VIRT, EffectsControls.EXTRA};
		for (int kind : kinds) {
			if (!fx.has(kind)) continue;
			col.addView(switchRow(p, p.ctx.getString(fx.label(kind)), fx.isOn(kind), on -> fx.setOn(kind, on)));
			if (fx.hasLevel(kind)) {
				col.addView(slider(p, Math.round(fx.level(kind) * 100), v -> fx.setLevel(kind, v / 100f)));
			}
			if (kind == EffectsControls.EQ) {
				String preset = fx.preset(p.ctx);
				LinearLayout row = p.row(p.ctx.getString(R.string.preset), (preset == null) ? "" : preset, () -> {
				});
				TextView value = (TextView) row.getChildAt(1);
				row.setOnClickListener(v -> {
					fx.nextPreset();
					String n = fx.preset(p.ctx);
					if (value != null) value.setText((n == null) ? "" : n);
				});
				col.addView(row);
				float[] bands = fx.bands();
				if (bands != null) {
					for (int i = 0; i < bands.length; i++) {
						int band = i;
						col.addView(slider(p, Math.round(bands[i] * 100), v -> fx.setBand(band, v / 100f)));
					}
				}
			}
		}
		return sv;
	}

	private interface OnBool {
		void on(boolean v);
	}

	private interface OnInt {
		void on(int v);
	}

	private static View switchRow(SidePanel p, String label, boolean on, OnBool l) {
		LinearLayout r = new LinearLayout(p.ctx);
		r.setOrientation(LinearLayout.HORIZONTAL);
		r.setGravity(android.view.Gravity.CENTER_VERTICAL);
		r.setPadding(p.dp(14), p.dp(8), p.dp(10), p.dp(8));
		r.setMinimumHeight(p.dp(48));
		r.setBackground(p.pressable(p.chipFill, 14));
		r.setClickable(true);
		r.setFocusable(true);
		TextView t = p.text(label, 15, p.primary, true);
		r.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(p.ctx);
		sw.setChecked(on);
		sw.setThumbTintList(ColorStateList.valueOf(p.accent));
		sw.setTrackTintList(ColorStateList.valueOf((p.accent & 0x00FFFFFF) | 0x66000000));
		sw.setOnCheckedChangeListener((b, c) -> l.on(c));
		// The whole row is the button, so the steering wheel's click lands on it.
		sw.setClickable(false);
		sw.setFocusable(false);
		r.setOnClickListener(v -> sw.toggle());
		r.addView(sw, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = p.dp(4);
		lp.bottomMargin = p.dp(4);
		r.setLayoutParams(lp);
		return r;
	}

	private static View slider(SidePanel p, int value, OnInt l) {
		StepSeekBar sb = new StepSeekBar(p.ctx);
		sb.setMax(100);
		sb.setProgress(value);
		sb.setStep(5);
		sb.setProgressTintList(ColorStateList.valueOf(p.accent));
		sb.setThumbTintList(ColorStateList.valueOf(p.accent));
		sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
				if (fromUser) l.on(progress);
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});
		sb.setPadding(p.dp(18), p.dp(6), p.dp(18), p.dp(10));
		sb.setFocusable(true);
		sb.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return sb;
	}

	// ---------------------------------------------------------------------------------------------
	// Captions

	public static void captions(MainActivityDelegate a) {
		boolean panel = SidePanel.isVideoUp(a) && a.getPrefs().getBooleanPref(MainActivityPrefs.SIDE_PANEL_CAPTIONS);
		if (!panel) {
			a.exitVideoMode();
			a.showFragment(R.id.captions_fragment);
			return;
		}
		SidePanel.toggle(a, "captions", R.drawable.captions, R.string.captions_settings, MainActivityPrefs.SIDE_PANEL_CAPTIONS_PERCENT, p -> captionsBody(p));
	}

	private static View captionsBody(SidePanel p) {
		MainActivityDelegate a = p.activity();
		MainActivityPrefs prefs = a.getPrefs();
		ScrollView sv = new ScrollView(p.ctx);
		LinearLayout col = new LinearLayout(p.ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		sv.addView(col, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		col.addView(switchRow(p, p.ctx.getString(R.string.captions_show), prefs.getBooleanPref(MainActivityPrefs.CAPTIONS_ON),
				on -> prefs.applyBooleanPref(MainActivityPrefs.CAPTIONS_ON, on)));
		col.addView(switchRow(p, p.ctx.getString(R.string.captions_auto), prefs.getBooleanPref(MainActivityPrefs.CAPTIONS_AUTO),
				on -> prefs.applyBooleanPref(MainActivityPrefs.CAPTIONS_AUTO, on)));
		col.addView(switchRow(p, p.ctx.getString(R.string.captions_romaji), prefs.getBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI),
				on -> CaptionLang.toggleRomajiTo(on)));

		// Which language is shown now, and the others to pick from.
		col.addView(p.heading(p.ctx.getString(R.string.captions_lang)));
		String lang = prefs.getStringPref(MainActivityPrefs.CAPTIONS_LANG);
		java.util.List<TextView> marks = new java.util.ArrayList<>();
		for (String code : CaptionLang.CODES) {
			LinearLayout row = p.row(CaptionLang.name(p.ctx, code), code.equals(lang) ? "✓" : "", null);
			TextView mark = (TextView) row.getChildAt(1);
			mark.setTag(code);
			marks.add(mark);
			row.setOnClickListener(v -> {
				CaptionLang.setLanguage(code);
				for (TextView m : marks) m.setText(code.equals(m.getTag()) ? "✓" : "");
			});
			col.addView(row);
		}
		col.addView(p.heading(p.ctx.getString(R.string.captions_translation)));
		String trNow = prefs.getStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION);
		LinearLayout trRow = p.row(p.ctx.getString(R.string.captions_translation),
				trNow.isEmpty() ? p.ctx.getString(R.string.captions_translation_off) : CaptionLang.name(p.ctx, trNow), null);
		TextView trValue = (TextView) trRow.getChildAt(1);
		trRow.setOnClickListener(v -> CaptionLang.showTranslationMenu(a, () -> trValue.postDelayed(() -> {
			String t = prefs.getStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION);
			trValue.setText(t.isEmpty() ? p.ctx.getString(R.string.captions_translation_off) : CaptionLang.name(p.ctx, t));
		}, 300)));
		col.addView(trRow);

		col.addView(p.heading(p.ctx.getString(R.string.caption_size)));
		StepSeekBar size = (StepSeekBar) slider(p, 0, v -> prefs.applyIntPref(MainActivityPrefs.CAPTION_SIZE, 50 + v * 250 / 100));
		size.setProgress((prefs.getIntPref(MainActivityPrefs.CAPTION_SIZE) - 50) * 100 / 250);
		col.addView(size);

		col.addView(p.row(p.ctx.getString(R.string.captions_more_settings), null, () -> {
			a.exitVideoMode();
			a.showFragment(R.id.captions_fragment);
		}));
		return sv;
	}
}
