package me.aap.fermata.ui.view;

import android.animation.ArgbEvaluator;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.addon.power.PowerSaver;

/**
 * One sound of the Ambience tab as a card: dark and quiet while it is off, and when it is on its
 * own little weather (an {@link AmbienceScene} clipped to the card) glowing under an icon disc, a
 * tinted border and an intensity slider that fades in. Everything follows the sound's fade, so
 * the card brightens and dims along with what is heard. A tap switches the sound on or off.
 */
public final class AmbienceCardView extends LinearLayout {
	private static final ArgbEvaluator ARGB = new ArgbEvaluator();
	private final AmbienceEffect fx;
	private final AmbienceEngine engine = AmbienceEngine.get();
	private AmbienceScene scene;
	private final float[] lv = new float[AmbienceEffect.values().length];
	private final AmbienceScene.Clock clock = new AmbienceScene.Clock(this, this::onFrame);
	private final Runnable changed = this::sync;
	private final AmbienceEngine.FlashListener flashListener = s -> {
		if (animated()) {
			this.scene.flash(s);
			clock.start();
		}
	};
	private final float dp;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final Path clip = new Path();
	private final GradientDrawable disc = new GradientDrawable();
	private final GradientDrawable statePill = new GradientDrawable();
	private final ImageView icon;
	private final TextView state;
	private final TextView hint;
	private final SeekBar bar;
	private Shader offShader, onShader;
	private float fade;
	private boolean attached;
	private boolean lastOn;

	public AmbienceCardView(Context ctx, AmbienceEffect fx) {
		super(ctx);
		this.fx = fx;
		dp = ctx.getResources().getDisplayMetrics().density;
		scene = new AmbienceScene(dp, 31L * (fx.ordinal() + 1));
		setOrientation(VERTICAL);
		setWillNotDraw(false);
		setClickable(true);
		setFocusable(true);
		setPadding(px(16), px(14), px(16), px(10));
		setMinimumHeight(px(158));
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setStrokeWidth(dp * 1.5f);

		LinearLayout top = new LinearLayout(ctx);
		top.setGravity(Gravity.CENTER_VERTICAL);
		FrameLayout discHolder = new FrameLayout(ctx);
		disc.setShape(GradientDrawable.OVAL);
		discHolder.setBackground(disc);
		icon = new ImageView(ctx);
		icon.setImageResource(fx.icon);
		discHolder.addView(icon, new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER));
		top.addView(discHolder, new LayoutParams(px(42), px(42)));
		View spacer = new View(ctx);
		top.addView(spacer, new LayoutParams(0, 1, 1f));
		state = text(ctx, 12, true);
		state.setPadding(px(10), px(4), px(10), px(4));
		statePill.setCornerRadius(px(12));
		state.setBackground(statePill);
		top.addView(state, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		TextView name = text(ctx, 17, true);
		name.setText(fx.name);
		LayoutParams nlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		nlp.topMargin = px(12);
		addView(name, nlp);
		TextView sub = text(ctx, 12.5f, false);
		sub.setText(fx.subtitle);
		sub.setTextColor(0xB3FFFFFF);
		sub.setMaxLines(2);
		addView(sub, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		View grow = new View(ctx);
		addView(grow, new LayoutParams(1, 0, 1f));
		// The bottom line: a hint while off, the intensity slider once on (they cross-fade).
		FrameLayout bottom = new FrameLayout(ctx);
		hint = text(ctx, 12.5f, false);
		hint.setText(R.string.ambience_tap_hint);
		hint.setTextColor(0x99FFFFFF);
		bottom.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
		bar = new SeekBar(ctx);
		bar.setMax(100);
		bar.setPadding(px(10), 0, px(10), 0);
		bar.setProgressTintList(ColorStateList.valueOf(fx.tint));
		bar.setThumbTintList(ColorStateList.valueOf(fx.tint));
		bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
		bar.setAlpha(0f);
		bar.setEnabled(false);
		bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
				if (!fromUser) return;
				engine.setIntensity(fx, progress);
				updateState();
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});
		bottom.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
				px(32), Gravity.CENTER_VERTICAL));
		LayoutParams blp = new LayoutParams(LayoutParams.MATCH_PARENT, px(32));
		blp.topMargin = px(6);
		addView(bottom, blp);

		setOnClickListener(v -> {
			performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
			engine.toggle(fx);
			pop();
		});
		bar.setProgress(engine.getIntensity(fx));
		lastOn = engine.isOn(fx);
		hint.setAlpha(lastOn ? 0f : 1f);
		bar.setAlpha(lastOn ? 1f : 0f);
		bar.setEnabled(lastOn);
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

	private boolean animated() {
		return AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.ANIM_TAB) &&
				!PowerSaver.get().isActive();
	}

	/** A little spring when tapped. */
	private void pop() {
		animate().cancel();
		setScaleX(0.95f);
		setScaleY(0.95f);
		animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(2.5f))
				.start();
	}

	private void updateState() {
		boolean on = engine.isOn(fx);
		state.setText(on ? getContext().getString(R.string.ambience_state_on, engine.getIntensity(fx)) :
				getContext().getString(R.string.ambience_state_off));
		setContentDescription(getContext().getString(fx.name) + ", " + state.getText());
		setSelected(on);
	}

	/** The engine's state changed (this sound switched, the fade moved): everything is drawn again. */
	private void sync() {
		boolean on = engine.isOn(fx);
		if (on != lastOn) {
			lastOn = on;
			hint.animate().alpha(on ? 0f : 1f).setDuration(220).start();
			bar.animate().alpha(on ? 1f : 0f).setDuration(260).start();
			bar.setEnabled(on);
			if (on) bar.setProgress(engine.getIntensity(fx));
		}
		updateState();
		if (attached) clock.start();
		invalidate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		attached = true;
		engine.addListener(changed);
		if (fx == AmbienceEffect.THUNDER) engine.addFlashListener(flashListener);
		sync();
	}

	@Override
	protected void onDetachedFromWindow() {
		attached = false;
		engine.removeListener(changed);
		engine.removeFlashListener(flashListener);
		clock.stop();
		super.onDetachedFromWindow();
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible && attached) clock.start();
		else clock.stop();
	}

	private boolean onFrame(float dt) {
		fade = engine.getFade(fx);
		boolean anim = animated();
		java.util.Arrays.fill(lv, 0f);
		float shown = engine.getVisual(fx);
		boolean active = shown > 0.003f;
		if (anim) {
			lv[fx.ordinal()] = shown;
			if (active || scene.isActive(lv)) scene.step(dt, lv);
		}
		applyColors();
		invalidate();
		return active || (anim && scene.isActive(lv));
	}

	private void applyColors() {
		int discOff = 0x24FFFFFF;
		disc.setColor((Integer) ARGB.evaluate(fade, discOff, fx.tint));
		icon.setImageTintList(ColorStateList.valueOf((Integer) ARGB.evaluate(fade, 0xE6FFFFFF, fx.deep)));
		statePill.setColor((Integer) ARGB.evaluate(fade, 0x1FFFFFFF, 0x40000000 | (fx.tint & 0xFFFFFF)));
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		rect.set(0, 0, w, h);
		float r = dp * 24f;
		clip.rewind();
		clip.addRoundRect(rect, r, r, Path.Direction.CW);
		int offTop = (Integer) ARGB.evaluate(0.5f, fx.deep, 0xFF1B1C24);
		int offBot = (Integer) ARGB.evaluate(0.7f, fx.deep, 0xFF121319);
		int onTop = fx.deep;
		int onBot = (Integer) ARGB.evaluate(0.3f, fx.deep, fx.tint);
		offShader = new LinearGradient(0, 0, 0, h, offTop, offBot, Shader.TileMode.CLAMP);
		onShader = new LinearGradient(0, 0, w * 0.4f, h, onTop, onBot, Shader.TileMode.CLAMP);
		fade = engine.getFade(fx);
		applyColors();
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((offShader == null) || (w == 0)) return;
		c.save();
		c.clipPath(clip);
		paint.setShader(offShader);
		paint.setAlpha(255);
		c.drawRect(rect, paint);
		paint.setShader(onShader);
		paint.setAlpha(Math.round(255 * fade));
		c.drawRect(rect, paint);
		paint.setShader(null);
		if (animated() && (lv[fx.ordinal()] > 0.003f || scene.isActive(lv))) {
			scene.draw(c, w, h, lv);
		}
		c.restore();
		stroke.setColor((Integer) ARGB.evaluate(fade, 0x1FFFFFFF, (fx.tint & 0xFFFFFF) | 0xB0000000));
		float inset = stroke.getStrokeWidth() / 2f;
		c.drawRoundRect(inset, inset, w - inset, h - inset, dp * 24f, dp * 24f, stroke);
	}
}
