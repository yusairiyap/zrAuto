package me.aap.fermata.ui.home.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.EffectsControls;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.ChipFlowLayout;

/**
 * The sound effects of the player that plays (Android's equalizer, bass boost, virtualizer and
 * loudness; YouTube's own equalizer, bass, virtualizer and reverb) as switches in the card itself,
 * the equalizer's bands as bars above them, and a chip that steps through its presets. With nothing
 * playing, YouTube's are shown: their settings stay. The header opens the full Audio effects screen.
 */
final class EffectsWidget extends HomeWidgetView {
	private static final long REFRESH_MS = 2500;
	private static final int[] KINDS = {EffectsControls.EQ, EffectsControls.BASS, EffectsControls.VIRT,
			EffectsControls.EXTRA};

	private LinearLayout column;
	private View headerRow;
	private Bars bars;
	private TextView status;
	private ChipFlowLayout chips;
	@Nullable
	private EffectsControls source;
	private String shownSignature = "";
	private final List<TextView> toggles = new ArrayList<>();
	private TextView presetChip;
	private final Runnable refresh = this::read;
	private final MediaSessionCallback.Listener listener = new MediaSessionCallback.Listener() {
		@Override
		public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
			post(EffectsWidget.this::read);
		}
	};

	EffectsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> host.run(Action.AUDIO_EFFECTS));
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		bars = new Bars(ctx);
		bars.setClickable(true);
		bars.setFocusable(true);
		bars.setContentDescription(ctx.getString(R.string.action_audio_effects));
		bars.setOnClickListener(v -> host.run(Action.AUDIO_EFFECTS));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		blp.topMargin = px(8);
		column.addView(bars, blp);
		status = text(ROLE_SECONDARY, 12.5f, false);
		status.setSingleLine(true);
		status.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(22));
		slp.topMargin = px(6);
		column.addView(status, slp);
		chips = new ChipFlowLayout(ctx);
		column.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("bars", ctx.getString(R.string.home_opt_effects_bars), true));
		l.add(HomeOption.sw("switches", ctx.getString(R.string.home_opt_effects_switches), true));
		return l;
	}

	@Override
	protected void onStyleChanged() {
		if (bars == null) return;
		bars.setColors(color(ROLE_ACCENT), softFill());
		paintChips();
	}

	@Override
	protected void onBind() {
		layoutFor();
		read();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column == null) return;
		layoutFor();
		read();
	}

	private void layoutFor() {
		int h = getCellsH();
		boolean strip = h == 1;
		boolean wantBars = (h >= 3) && optBool("bars", true);
		boolean wantSwitches = optBool("switches", true);
		headerRow.setVisibility(strip ? GONE : VISIBLE);
		bars.setVisibility(wantBars ? VISIBLE : GONE);
		status.setVisibility(wantBars ? VISIBLE : GONE);
		chips.setVisibility(wantSwitches ? VISIBLE : GONE);
		// With neither bars nor switches the card is the header and the status, so say how it is.
		if (!wantBars && !wantSwitches) status.setVisibility(VISIBLE);
	}

	@Override
	protected void onActive() {
		host.activity().getMediaSessionCallback().addBroadcastListener(listener);
		read();
	}

	@Override
	protected void onInactive() {
		host.activity().getMediaSessionCallback().removeBroadcastListener(listener);
		removeCallbacks(refresh);
	}

	/** The effects of what plays; YouTube's when nothing does, none for a player without any. */
	@Nullable
	private EffectsControls pick() {
		MediaEngine eng = host.activity().getMediaSessionCallback().getEngine();
		if (eng == null) return MusicPlayer.getYoutubeEffects();
		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) return MusicPlayer.getYoutubeEffects();
		if (!eng.supportsAudioEffects()) return null;
		try {
			return EffectsControls.of(eng.ensureAudioEffects());
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private void read() {
		if (column == null) return;
		removeCallbacks(refresh);
		EffectsControls src = pick();
		source = src;
		MediaEngine eng = host.activity().getMediaSessionCallback().getEngine();
		if (src == null) {
			bars.setLevels(null);
			status.setText((eng == null) ? R.string.home_effects_idle : R.string.home_effects_none);
			buildChips(null);
		} else {
			boolean on = src.isOn(EffectsControls.EQ);
			bars.setLevels(on ? src.bands() : null);
			String preset = src.preset(ctx);
			if (!src.has(EffectsControls.EQ)) status.setText(R.string.home_effects_on);
			else if (!on) status.setText(R.string.home_effects_off);
			else if (preset != null) status.setText(ctx.getString(R.string.home_effects_preset, preset));
			else status.setText(R.string.home_effects_on);
			buildChips(src);
		}
		if (isActive()) postDelayed(refresh, REFRESH_MS);
	}

	/** Builds the switches when the set of effects changed, else only paints their state. */
	private void buildChips(@Nullable EffectsControls src) {
		StringBuilder sig = new StringBuilder();
		if (src != null) {
			sig.append(System.identityHashCode(src.getClass()));
			for (int k : KINDS) sig.append(src.has(k) ? '1' : '0').append(src.has(k) ? src.label(k) : 0);
			sig.append(getCellsH());
		}
		String signature = sig.toString();
		if (!signature.equals(shownSignature)) {
			shownSignature = signature;
			chips.removeAllViews();
			toggles.clear();
			presetChip = null;
			if (src != null) {
				for (int k : KINDS) {
					if (!src.has(k)) continue;
					final int kind = k;
					TextView t = switchChip(ctx.getString(src.label(k)), () -> {
						EffectsControls s = source;
						if (s == null) return;
						s.setOn(kind, !s.isOn(kind));
						paintChips();
						read();
					});
					t.setTag(k);
					toggles.add(t);
					chips.addView(t);
				}
				if (src.has(EffectsControls.EQ) && (getCellsH() >= 2)) {
					presetChip = switchChip("", () -> {
						EffectsControls s = source;
						if (s == null) return;
						if (!s.isOn(EffectsControls.EQ)) s.setOn(EffectsControls.EQ, true);
						s.nextPreset();
						read();
					});
					chips.addView(presetChip);
				}
			}
		}
		paintChips();
	}

	private TextView switchChip(CharSequence label, Runnable click) {
		TextView t = new TextView(ctx);
		t.setText(label);
		t.setTextSize(12.5f);
		t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		t.setSingleLine(true);
		t.setGravity(Gravity.CENTER);
		t.setPadding(px(12), 0, px(12), 0);
		t.setMinHeight(px(34));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> click.run());
		return t;
	}

	/** Lit when on (the card's accent), faint when off. */
	private void paintChips() {
		EffectsControls src = source;
		for (TextView t : toggles) {
			boolean on = (src != null) && src.isOn((Integer) t.getTag());
			t.setBackground(HomeUi.pressable(ripple(), on ? color(ROLE_ACCENT) : softFill(), px(17)));
			t.setTextColor(on ? color(ROLE_ON_ACCENT) : color(ROLE_PRIMARY));
		}
		if ((presetChip != null) && (src != null)) {
			String preset = src.preset(ctx);
			presetChip.setText(ctx.getString(R.string.home_effects_preset_chip,
					(preset != null) ? preset : ctx.getString(R.string.eq_manual)));
			presetChip.setBackground(HomeUi.pressable(ripple(), softFill(), px(17)));
			presetChip.setTextColor(color(ROLE_PRIMARY));
		}
	}

	/** Rounded bars that glide to their new heights. */
	private static final class Bars extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private float[] shown = new float[0];
		private float[] target = new float[0];
		private boolean live;
		private int on;
		private int off;
		@Nullable
		private ValueAnimator anim;

		Bars(Context ctx) {
			super(ctx);
		}

		void setColors(int on, int off) {
			this.on = on;
			this.off = off;
			invalidate();
		}

		/** Heights from 0 to 1, one per band; null when there is nothing to show (the bars rest). */
		void setLevels(@Nullable float[] levels) {
			float[] to = (levels != null) ? levels : rest(shown.length > 0 ? shown.length : 5);
			live = levels != null;
			if (java.util.Arrays.equals(to, target)) {
				invalidate();
				return;
			}
			if (shown.length != to.length) shown = rest(to.length);
			final float[] from = shown.clone();
			target = to;
			if (anim != null) anim.cancel();
			if (!isShown()) {
				shown = to.clone();
				invalidate();
				return;
			}
			ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
			a.setDuration(450);
			a.setInterpolator(new android.view.animation.DecelerateInterpolator());
			a.addUpdateListener(v -> {
				float f = (float) v.getAnimatedValue();
				for (int i = 0; i < shown.length; i++) shown[i] = from[i] + (target[i] - from[i]) * f;
				invalidate();
			});
			anim = a;
			a.start();
		}

		private static float[] rest(int n) {
			float[] r = new float[n];
			for (int i = 0; i < n; i++) r[i] = 0.18f + 0.1f * (float) Math.sin(i * 1.3);
			return r;
		}

		@Override
		protected void onDraw(Canvas c) {
			int n = shown.length;
			if (n == 0) shown = rest(5);
			n = shown.length;
			float w = getWidth();
			float h = getHeight();
			if ((w == 0) || (h == 0)) return;
			float gap = Math.max(4f, w / (n * 5f));
			float bw = (w - gap * (n - 1)) / n;
			paint.setColor(live ? on : off);
			for (int i = 0; i < n; i++) {
				float lv = Math.max(0.08f, Math.min(1f, shown[i]));
				float x = i * (bw + gap);
				rect.set(x, h - h * lv, x + bw, h);
				c.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
			}
		}
	}
}
