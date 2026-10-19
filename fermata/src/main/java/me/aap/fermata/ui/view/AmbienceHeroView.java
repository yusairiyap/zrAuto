package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.addon.power.PowerSaver;

/**
 * The top of the Ambience tab: a sky that takes the colours and the weather of everything that is
 * on (an {@link AmbienceScene} showing the whole mix), the names of what plays, a Start / Stop all
 * button and the timer. Idle it is a still, quiet gradient and costs nothing; the frames only
 * run while some sound is audible.
 */
public final class AmbienceHeroView extends FrameLayout {
	private static final int IDLE_TOP = 0xFF23254F;
	private static final int IDLE_BOTTOM = 0xFF0F1026;
	private final AmbienceEngine engine = AmbienceEngine.get();
	private final AmbienceScene scene;
	private final float[] lv = new float[AmbienceEffect.values().length];
	private final AmbienceScene.Clock clock = new AmbienceScene.Clock(this, this::onFrame);
	private final Runnable changed = this::sync;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable countdown = this::tickCountdown;
	private final AmbienceEngine.FlashListener flashListener = s -> {
		if (animated()) {
			this.scene.flash(s);
			clock.start();
		}
	};
	private final float dp;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final Path clip = new Path();
	private final ImageView mark;
	private final TextView title;
	private final TextView subtitle;
	private final TextView action;
	private final TextView timer;
	private final GradientDrawable actionBg = new GradientDrawable();
	private final GradientDrawable timerBg = new GradientDrawable();
	private LinearGradient shade;
	private LinearGradient sky;
	private int skyTop = IDLE_TOP, skyBottom = IDLE_BOTTOM;
	private boolean attached;
	private boolean lastOn;
	private Runnable onTimerClick;

	public AmbienceHeroView(Context ctx) {
		super(ctx);
		dp = ctx.getResources().getDisplayMetrics().density;
		scene = new AmbienceScene(dp, 99);
		setWillNotDraw(false);
		setPadding(px(20), px(18), px(20), px(16));

		mark = new ImageView(ctx);
		mark.setImageResource(R.drawable.ambience);
		mark.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
		mark.setAlpha(0.3f);
		FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(px(64), px(64),
				Gravity.TOP | Gravity.END);
		addView(mark, mlp);

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		title = text(ctx, 26, true);
		title.setMaxLines(2);
		title.setText(R.string.ambience_title);
		subtitle = text(ctx, 13.5f, false);
		subtitle.setTextColor(0xCCFFFFFF);
		subtitle.setText(R.string.ambience_hero_idle);
		texts.addView(title);
		texts.addView(subtitle);
		FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
				Gravity.TOP | Gravity.START);
		tlp.setMarginEnd(px(72));
		addView(texts, tlp);

		timer = text(ctx, 13, true);
		timerBg.setCornerRadius(px(18));
		timerBg.setColor(0x33FFFFFF);
		timer.setBackground(timerBg);
		timer.setGravity(Gravity.CENTER_VERTICAL);
		timer.setCompoundDrawablePadding(px(6));
		timer.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.timer, 0, 0, 0);
		timer.setCompoundDrawableTintList(ColorStateList.valueOf(0xFFFFFFFF));
		timer.setPadding(px(12), 0, px(14), 0);
		timer.setClickable(true);
		timer.setFocusable(true);
		timer.setOnClickListener(v -> {
			if (onTimerClick != null) onTimerClick.run();
		});
		addView(timer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(36),
				Gravity.BOTTOM | Gravity.START));

		action = text(ctx, 14.5f, true);
		actionBg.setCornerRadius(px(22));
		action.setBackground(actionBg);
		action.setGravity(Gravity.CENTER_VERTICAL);
		action.setCompoundDrawablePadding(px(8));
		action.setPadding(px(16), 0, px(20), 0);
		action.setClickable(true);
		action.setFocusable(true);
		action.setOnClickListener(v -> {
			engine.toggleAll();
			action.animate().cancel();
			action.setScaleX(0.9f);
			action.setScaleY(0.9f);
			action.animate().scaleX(1f).scaleY(1f).setDuration(380)
					.setInterpolator(new OvershootInterpolator(2.5f)).start();
		});
		addView(action, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44),
				Gravity.BOTTOM | Gravity.END));
		lastOn = engine.anyOn();
		sync();
	}

	private int px(float v) {
		return Math.round(v * dp);
	}

	private TextView text(Context ctx, float sp, boolean bold) {
		TextView t = new TextView(ctx);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(0xFFFFFFFF);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	/** Tapping the timer chip (the tab steps through the lengths). */
	public void setOnTimerClick(Runnable r) {
		onTimerClick = r;
	}

	private boolean animated() {
		return AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.ANIM_TAB) &&
				!PowerSaver.get().isActive();
	}

	/** Cross-fades to a new text; nothing happens when it is the one already shown. */
	private static void swap(TextView t, CharSequence s) {
		if (s.toString().contentEquals(t.getText())) {
			// A fade out that was cut short by a change back leaves it half transparent.
			t.animate().cancel();
			t.setAlpha(1f);
			return;
		}
		if (!t.isAttachedToWindow()) {
			t.setText(s);
			return;
		}
		t.animate().cancel();
		t.animate().alpha(0f).setDuration(120).withEndAction(() -> {
			t.setText(s);
			t.animate().alpha(1f).setDuration(200).start();
		}).start();
	}

	private void sync() {
		Context ctx = getContext();
		boolean on = engine.anyOn();
		boolean held = engine.isHeld();
		swap(title, on ? engine.describe(ctx) : ctx.getString(R.string.ambience_title));
		swap(subtitle, ctx.getString(held ? R.string.ambience_hero_paused :
				on ? R.string.ambience_hero_playing : R.string.ambience_hero_idle));
		action.setText(on ? R.string.ambience_stop_all : R.string.ambience_start);
		action.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.stop : R.drawable.play,
				0, 0, 0);
		int fg = on ? 0xFF14152B : 0xFFFFFFFF;
		action.setTextColor(fg);
		action.setCompoundDrawableTintList(ColorStateList.valueOf(fg));
		actionBg.setColor(on ? 0xF2FFFFFF : 0x33FFFFFF);
		if (on != lastOn) {
			lastOn = on;
			mark.animate().alpha(on ? 0.12f : 0.3f).setDuration(500).start();
		}
		updateTimer();
		handler.removeCallbacks(countdown);
		if (attached && (engine.getRemainingMs() >= 0)) handler.postDelayed(countdown, 1000);
		if (attached) clock.start();
		invalidate();
	}

	private void tickCountdown() {
		updateTimer();
		if (attached && (engine.getRemainingMs() >= 0) && isShown()) handler.postDelayed(countdown, 1000);
	}

	private void updateTimer() {
		Context ctx = getContext();
		long left = engine.getRemainingMs();
		int min = engine.getLengthMinutes();
		String s;
		if (left >= 0) {
			long sec = (left + 999) / 1000;
			String t = (sec >= 3600) ? String.format(java.util.Locale.getDefault(), "%d:%02d:%02d",
					sec / 3600, sec / 60 % 60, sec % 60) :
					String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
			s = ctx.getString(R.string.ambience_left, t);
		} else if (min <= 0) {
			s = ctx.getString(R.string.ambience_length_forever);
		} else {
			s = lengthLabel(ctx, min);
		}
		if (!s.contentEquals(timer.getText())) timer.setText(s);
	}

	/** "15 min", "2 h". */
	public static String lengthLabel(Context ctx, int min) {
		if (min <= 0) return ctx.getString(R.string.ambience_length_forever);
		if ((min % 60) == 0) return ctx.getString(R.string.ambience_hours_short, min / 60);
		return ctx.getString(R.string.ambience_minutes_short, min);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		attached = true;
		engine.addListener(changed);
		engine.addFlashListener(flashListener);
		sync();
	}

	@Override
	protected void onDetachedFromWindow() {
		attached = false;
		engine.removeListener(changed);
		engine.removeFlashListener(flashListener);
		handler.removeCallbacks(countdown);
		clock.stop();
		super.onDetachedFromWindow();
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible && attached) {
			clock.start();
			sync();
		} else {
			clock.stop();
			handler.removeCallbacks(countdown);
		}
	}

	private boolean onFrame(float dt) {
		float sum = 0f, r = 0f, g = 0f, b = 0f, r2 = 0f, g2 = 0f, b2 = 0f;
		boolean anim = animated();
		for (AmbienceEffect e : AmbienceEffect.values()) {
			float shown = engine.getVisual(e);
			lv[e.ordinal()] = anim ? shown : 0f;
			float f = engine.getFade(e);
			if (f <= 0f) continue;
			sum += f;
			int d = e.deep;
			r += f * ((d >> 16) & 0xFF);
			g += f * ((d >> 8) & 0xFF);
			b += f * (d & 0xFF);
			int t = e.tint;
			r2 += f * ((t >> 16) & 0xFF);
			g2 += f * ((t >> 8) & 0xFF);
			b2 += f * (t & 0xFF);
		}
		if (sum > 0.001f) {
			float k = Math.min(1f, sum);
			skyTop = mix(IDLE_TOP, r / sum, g / sum, b / sum, k, 1f);
			// The bottom takes some of the bright colour, like a horizon.
			skyBottom = mix(IDLE_BOTTOM, r / sum, g / sum, b / sum, k, 0.5f);
			skyBottom = blend(skyBottom, 0xFF000000 | (Math.round(r2 / sum) << 16) |
					(Math.round(g2 / sum) << 8) | Math.round(b2 / sum), 0.22f * k);
		} else {
			skyTop = IDLE_TOP;
			skyBottom = IDLE_BOTTOM;
		}
		sky = null;
		boolean active = scene.isActive(lv) || (sum > 0.001f);
		if (anim && active) scene.step(dt, lv);
		invalidate();
		return active;
	}

	private static int mix(int idle, float r, float g, float b, float k, float dark) {
		float ir = (idle >> 16) & 0xFF, ig = (idle >> 8) & 0xFF, ib = idle & 0xFF;
		int rr = Math.round((ir + (r * dark - ir) * k));
		int gg = Math.round((ig + (g * dark - ig) * k));
		int bb = Math.round((ib + (b * dark - ib) * k));
		return 0xFF000000 | (clamp(rr) << 16) | (clamp(gg) << 8) | clamp(bb);
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}

	private static int blend(int a, int b, float t) {
		int rr = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int gg = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bb = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | (clamp(rr) << 16) | (clamp(gg) << 8) | clamp(bb);
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		rect.set(0, 0, w, h);
		float r = dp * 28f;
		clip.rewind();
		clip.addRoundRect(rect, r, r, Path.Direction.CW);
		shade = new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP);
		sky = null;
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if (w == 0) return;
		if (sky == null) sky = new LinearGradient(0, 0, 0, h, skyTop, skyBottom, Shader.TileMode.CLAMP);
		c.save();
		c.clipPath(clip);
		paint.setShader(sky);
		c.drawRect(rect, paint);
		paint.setShader(null);
		boolean any = false;
		for (float v : lv) if (v > 0.003f) any = true;
		if (any || scene.isActive(lv)) scene.draw(c, w, h, lv);
		// A shade along the bottom keeps the button and the timer readable over any weather.
		paint.setShader(shade);
		c.drawRect(rect, paint);
		paint.setShader(null);
		c.restore();
	}
}
