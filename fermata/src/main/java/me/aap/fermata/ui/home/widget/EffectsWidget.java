package me.aap.fermata.ui.home.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.audiofx.Equalizer;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.engine.AudioEffects;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;

/**
 * The equalizer as it is set right now, drawn as bars (one per band, gliding when the settings or
 * the song's own preset change), and whether it is on. A tap anywhere on it opens the Audio effects
 * screen. With nothing playing there is no equalizer to read: the bars rest.
 */
final class EffectsWidget extends HomeWidgetView {
	private static final long REFRESH_MS = 2500;

	private LinearLayout column;
	private Bars bars;
	private TextView status;
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
		column.addView(header(() -> host.run(Action.AUDIO_EFFECTS)),
				new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
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
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected void onStyleChanged() {
		if (bars != null) bars.setColors(color(ROLE_ACCENT), softFill());
	}

	@Override
	protected void onBind() {
		read();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column == null) return;
		// A strip has no room for the bars' status line.
		status.setVisibility(h == 1 ? GONE : VISIBLE);
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

	/** Reads the equalizer of the engine that plays (if any) and shows it. */
	private void read() {
		if (column == null) return;
		removeCallbacks(refresh);
		MediaEngine eng = host.activity().getMediaSessionCallback().getEngine();
		AudioEffects fx = (eng == null) ? null : eng.getAudioEffects();
		Equalizer eq = (fx == null) ? null : fx.getEqualizer();
		if (eq == null) {
			bars.setLevels(null);
			status.setText((eng == null) ? R.string.home_effects_idle : R.string.home_effects_none);
		} else {
			try {
				short n = eq.getNumberOfBands();
				short[] range = eq.getBandLevelRange();
				float span = Math.max(1, range[1] - range[0]);
				float[] levels = new float[n];
				for (short i = 0; i < n; i++) levels[i] = (eq.getBandLevel(i) - range[0]) / span;
				boolean on = eq.getEnabled();
				bars.setLevels(on ? levels : null);
				String preset = null;
				short cur = eq.getCurrentPreset();
				if (cur >= 0) preset = eq.getPresetName(cur);
				if (!on) status.setText(R.string.home_effects_off);
				else if (preset != null) status.setText(ctx.getString(R.string.home_effects_preset, preset));
				else status.setText(R.string.home_effects_on);
			} catch (RuntimeException ex) {
				bars.setLevels(null);
				status.setText(R.string.home_effects_none);
			}
		}
		if (isActive()) postDelayed(refresh, REFRESH_MS);
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
