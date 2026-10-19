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
import me.aap.fermata.addon.ambience.AmbienceSamples;
import me.aap.fermata.addon.power.PowerSaver;

/**
 * One sound of the Ambience tab as a card, with two pages. The front is dark and quiet while the
 * sound is off, and when it is on has its own little weather (an {@link AmbienceScene} clipped to
 * the card) glowing under an icon disc, a tinted border and an intensity slider that fades in; a
 * tap switches the sound on or off. The tune button flips the card to its second page: the
 * intensity again, and the sound's own character slider (rain's surface, the fire's crackle, ...)
 * with the words for its two ends, and Reset. Both pages follow the sound's fade, so the card
 * brightens and dims along with what is heard.
 */
public final class AmbienceCardView extends FrameLayout {
	private static final ArgbEvaluator ARGB = new ArgbEvaluator();
	private final AmbienceEffect fx;
	private final AmbienceEngine engine = AmbienceEngine.get();
	private AmbienceScene scene;
	private final float[] lv = new float[AmbienceEffect.values().length];
	private final AmbienceScene.Clock clock = new AmbienceScene.Clock(this, this::onFrame);
	private final AmbienceSamples samples = AmbienceSamples.get();
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
	private final LinearLayout front;
	private final LinearLayout tune;
	private final ImageView icon;
	private final TextView state;
	private final TextView hint;
	private final SeekBar bar;
	private final SeekBar tuneLevel;
	private final SeekBar tuneOption;
	private final TextView tuneLevelValue;
	private final TextView tuneOptionValue;
	private TextView optLabel, optLo, optHi, srcSynth, srcRec;
	private LinearLayout srcRow;
	private Shader offShader, onShader;
	private float fade;
	private boolean attached;
	private boolean lastOn;
	private boolean tuning;
	private boolean syncing;

	public AmbienceCardView(Context ctx, AmbienceEffect fx) {
		super(ctx);
		this.fx = fx;
		dp = ctx.getResources().getDisplayMetrics().density;
		scene = new AmbienceScene(dp, 31L * (fx.ordinal() + 1));
		setWillNotDraw(false);
		setClickable(true);
		setFocusable(true);
		setMinimumHeight(px(204));
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setStrokeWidth(dp * 1.5f);

		// ---- Front page ----
		front = new LinearLayout(ctx);
		front.setOrientation(LinearLayout.VERTICAL);
		front.setPadding(px(16), px(14), px(16), px(10));
		addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		LinearLayout top = new LinearLayout(ctx);
		top.setGravity(Gravity.CENTER_VERTICAL);
		FrameLayout discHolder = new FrameLayout(ctx);
		disc.setShape(GradientDrawable.OVAL);
		discHolder.setBackground(disc);
		icon = new ImageView(ctx);
		icon.setImageResource(fx.icon);
		discHolder.addView(icon, new LayoutParams(px(22), px(22), Gravity.CENTER));
		top.addView(discHolder, new LinearLayout.LayoutParams(px(42), px(42)));
		top.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
		state = text(ctx, 12, true);
		state.setPadding(px(10), px(4), px(10), px(4));
		statePill.setCornerRadius(px(12));
		state.setBackground(statePill);
		top.addView(state, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));
		front.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));

		TextView name = text(ctx, 17, true);
		name.setText(fx.name);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		nlp.topMargin = px(12);
		front.addView(name, nlp);
		TextView sub = text(ctx, 12.5f, false);
		sub.setText(fx.subtitle);
		sub.setTextColor(0xB3FFFFFF);
		sub.setMaxLines(2);
		front.addView(sub, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));
		front.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f));

		// The bottom line: a hint while off, the intensity slider once on (they cross-fade), and the
		// tune button at the end.
		LinearLayout bottom = new LinearLayout(ctx);
		bottom.setGravity(Gravity.CENTER_VERTICAL);
		FrameLayout bottomMain = new FrameLayout(ctx);
		hint = text(ctx, 12.5f, false);
		hint.setText(R.string.ambience_tap_hint);
		hint.setTextColor(0x99FFFFFF);
		bottomMain.addView(hint, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
				Gravity.CENTER_VERTICAL));
		bar = newBar(ctx);
		bar.setPadding(px(10), 0, px(10), 0);
		bar.setAlpha(0f);
		bar.setEnabled(false);
		bottomMain.addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, px(32), Gravity.CENTER_VERTICAL));
		bottom.addView(bottomMain, new LinearLayout.LayoutParams(0, px(32), 1f));
		ImageView tuneBtn = new ImageView(ctx);
		tuneBtn.setImageResource(R.drawable.ambience_tune);
		tuneBtn.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
		tuneBtn.setPadding(px(6), px(6), px(6), px(6));
		tuneBtn.setBackground(roundFill(0x1FFFFFFF, 16));
		tuneBtn.setClickable(true);
		tuneBtn.setFocusable(true);
		tuneBtn.setContentDescription(ctx.getString(R.string.ambience_tune_for, ctx.getString(fx.name)));
		tuneBtn.setOnClickListener(v -> setTuning(true));
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(32), px(32));
		tlp.setMarginStart(px(6));
		bottom.addView(tuneBtn, tlp);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, px(32));
		blp.topMargin = px(6);
		front.addView(bottom, blp);

		// ---- Tune page ----
		tune = new LinearLayout(ctx);
		tune.setOrientation(LinearLayout.VERTICAL);
		tune.setPadding(px(16), px(12), px(16), px(10));
		tune.setClickable(true);
		tune.setAlpha(0f);
		tune.setVisibility(GONE);
		addView(tune, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		LinearLayout head = new LinearLayout(ctx);
		head.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = text(ctx, 16, true);
		title.setText(fx.name);
		head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		TextView reset = text(ctx, 12, true);
		reset.setText(R.string.ambience_reset);
		reset.setPadding(px(10), px(5), px(10), px(5));
		reset.setBackground(roundFill(0x26FFFFFF, 12));
		reset.setClickable(true);
		reset.setFocusable(true);
		reset.setOnClickListener(v -> {
			engine.resetEffect(fx);
			sync();
		});
		head.addView(reset, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));
		ImageView close = new ImageView(ctx);
		close.setImageResource(R.drawable.close_small);
		close.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
		close.setPadding(px(4), px(4), px(4), px(4));
		close.setClickable(true);
		close.setFocusable(true);
		close.setOnClickListener(v -> setTuning(false));
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(30), px(30));
		clp.setMarginStart(px(6));
		head.addView(close, clp);
		tune.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(30)));

		tuneLevelValue = text(ctx, 12, false);
		tuneLevel = newBar(ctx);
		addParam(ctx, ctx.getString(R.string.ambience_intensity), tuneLevelValue, tuneLevel, 8);
		tuneOptionValue = text(ctx, 12, false);
		tuneOption = newBar(ctx);
		optLabel = addParam(ctx, ctx.getString(fx.optName), tuneOptionValue, tuneOption, 6);
		// The words for the two ends of the character slider.
		LinearLayout ends = new LinearLayout(ctx);
		optLo = text(ctx, 11, false);
		optLo.setText(fx.optLow);
		optLo.setTextColor(0x99FFFFFF);
		optHi = text(ctx, 11, false);
		optHi.setText(fx.optHigh);
		optHi.setTextColor(0x99FFFFFF);
		optHi.setGravity(Gravity.END);
		ends.setPadding(px(12), 0, px(12), 0);
		ends.addView(optLo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		ends.addView(optHi, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		tune.addView(ends, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));

		// Synthesized or recorded: only for sounds that have a recording to download.
		srcRow = new LinearLayout(ctx);
		srcRow.setVisibility(GONE);
		srcSynth = pill(ctx);
		srcSynth.setText(R.string.ambience_src_synth);
		srcSynth.setOnClickListener(v -> engine.setRecorded(fx, false));
		srcRec = pill(ctx);
		srcRec.setOnClickListener(v -> engine.setRecorded(fx, true));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, px(26), 1f);
		slp.setMarginEnd(px(6));
		srcRow.addView(srcSynth, slp);
		srcRow.addView(srcRec, new LinearLayout.LayoutParams(0, px(26), 1f));
		LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		srlp.topMargin = px(10);
		tune.addView(srcRow, srlp);

		SeekBar.OnSeekBarChangeListener levelListener = new BarListener() {
			@Override
			void onUser(int progress) {
				engine.setIntensity(fx, progress);
				sync();
			}
		};
		bar.setOnSeekBarChangeListener(levelListener);
		tuneLevel.setOnSeekBarChangeListener(levelListener);
		tuneOption.setOnSeekBarChangeListener(new BarListener() {
			@Override
			void onUser(int progress) {
				if (usingTone()) engine.setTone(fx, progress);
				else engine.setOption(fx, progress);
				sync();
			}
		});

		setOnClickListener(v -> {
			if (tuning) return;
			performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
			engine.toggle(fx);
			pop();
		});
		lastOn = engine.isOn(fx);
		hint.setAlpha(lastOn ? 0f : 1f);
		bar.setAlpha(lastOn ? 1f : 0f);
		bar.setEnabled(lastOn);
		sync();
	}

	/** A slider that only reports what the user does (not what {@link #sync} sets). */
	private abstract class BarListener implements SeekBar.OnSeekBarChangeListener {
		abstract void onUser(int progress);

		@Override
		public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
			if (fromUser && !syncing) onUser(progress);
		}

		@Override
		public void onStartTrackingTouch(SeekBar s) {
		}

		@Override
		public void onStopTrackingTouch(SeekBar s) {
		}
	}

	private SeekBar newBar(Context ctx) {
		SeekBar b = new SeekBar(ctx);
		b.setMax(100);
		b.setProgressTintList(ColorStateList.valueOf(fx.tint));
		b.setThumbTintList(ColorStateList.valueOf(fx.tint));
		b.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
		return b;
	}

	/** A label and its value on one line, the slider under them. */
	private TextView addParam(Context ctx, String label, TextView value, SeekBar b, int topMargin) {
		LinearLayout row = new LinearLayout(ctx);
		TextView l = text(ctx, 12, false);
		l.setText(label);
		l.setTextColor(0xCCFFFFFF);
		row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		value.setTextColor(0xCCFFFFFF);
		row.addView(value);
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		rlp.topMargin = px(topMargin);
		tune.addView(row, rlp);
		b.setPadding(px(10), 0, px(10), 0);
		tune.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(26)));
		return l;
	}

	/** A small rounded switch option. */
	private TextView pill(Context ctx) {
		TextView t = text(ctx, 11.5f, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		t.setPadding(px(6), 0, px(6), 0);
		t.setBackground(roundFill(0x26FFFFFF, 13));
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	private void stylePill(TextView t, boolean selected) {
		((GradientDrawable) t.getBackground()).setColor(selected ? fx.tint : 0x26FFFFFF);
		t.setTextColor(selected ? fx.deep : 0xFFFFFFFF);
	}

	/** The tune page's character slider is the recording's tone while the recording is what plays. */
	private boolean usingTone() {
		return engine.isRecorded(fx) && samples.isReady(fx);
	}

	private GradientDrawable roundFill(int color, float radiusDp) {
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(px(radiusDp));
		return g;
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

	/** Flips between the two pages: one fades and shrinks away while the other grows into place. */
	private void setTuning(boolean on) {
		if (tuning == on) return;
		tuning = on;
		View from = on ? front : tune;
		View to = on ? tune : front;
		from.animate().cancel();
		to.animate().cancel();
		to.setVisibility(VISIBLE);
		to.setScaleX(on ? 1.04f : 0.96f);
		to.setScaleY(on ? 1.04f : 0.96f);
		to.setAlpha(0f);
		to.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start();
		from.animate().alpha(0f).scaleX(on ? 0.96f : 1.04f).scaleY(on ? 0.96f : 1.04f).setDuration(200)
				.withEndAction(() -> {
					if (tuning == on) from.setVisibility(GONE);
				}).start();
		if (on) sync();
	}

	/** The Synthesized / Recorded choice and whether the recording is loaded yet. */
	private void updateSource() {
		boolean has = samples.has(fx) && !fx.realOnly;
		srcRow.setVisibility(has ? VISIBLE : GONE);
		if (!has) return;
		boolean rec = engine.isRecorded(fx);
		stylePill(srcSynth, !rec);
		stylePill(srcRec, rec);
		Context ctx = getContext();
		String text;
		switch (samples.getState(fx)) {
			case READY -> text = ctx.getString(R.string.ambience_src_rec);
			case LOADING -> text = ctx.getString(R.string.ambience_src_loading);
			case FAILED -> text = ctx.getString(R.string.ambience_src_retry);
			default -> text = ctx.getString(R.string.ambience_src_rec);
		}
		srcRec.setText(text);
	}

	private void updateState() {
		boolean on = engine.isOn(fx);
		state.setText(on ? getContext().getString(R.string.ambience_state_on, engine.getIntensity(fx)) :
				getContext().getString(R.string.ambience_state_off));
		setContentDescription(getContext().getString(fx.name) + ", " + state.getText());
		setSelected(on);
	}

	/** The engine's state changed (this sound switched, a slider moved): everything is drawn again. */
	private void sync() {
		boolean on = engine.isOn(fx);
		if (on != lastOn) {
			lastOn = on;
			hint.animate().alpha(on ? 0f : 1f).setDuration(220).start();
			bar.animate().alpha(on ? 1f : 0f).setDuration(260).start();
			bar.setEnabled(on);
		}
		// Both intensity sliders and the character one show what the engine holds.
		syncing = true;
		int level = engine.getIntensity(fx);
		boolean tone = usingTone();
		int option = tone ? engine.getTone(fx) : engine.getOption(fx);
		if (bar.getProgress() != level) bar.setProgress(level);
		if (tuneLevel.getProgress() != level) tuneLevel.setProgress(level);
		if (tuneOption.getProgress() != option) tuneOption.setProgress(option);
		syncing = false;
		optLabel.setText(tone ? R.string.ambience_tone : fx.optName);
		optLo.setText(tone ? R.string.ambience_tone_lo : fx.optLow);
		optHi.setText(tone ? R.string.ambience_tone_hi : fx.optHigh);
		updateSource();
		tuneLevelValue.setText(getContext().getString(R.string.ambience_percent, level));
		tuneOptionValue.setText(getContext().getString(R.string.ambience_percent, option));
		updateState();
		if (attached) clock.start();
		invalidate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		attached = true;
		engine.addListener(changed);
		samples.addListener(changed);
		if (fx == AmbienceEffect.THUNDER) engine.addFlashListener(flashListener);
		sync();
	}

	@Override
	protected void onDetachedFromWindow() {
		attached = false;
		engine.removeListener(changed);
		samples.removeListener(changed);
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
		// Also while the sound is on but not yet heard: the audio thread takes a moment to start,
		// and a loop that stopped in that gap would only wake when another sound changed.
		boolean active = (shown > 0.003f) || engine.isPlaying(fx);
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
		if (animated() && ((lv[fx.ordinal()] > 0.003f) || scene.isActive(lv))) {
			scene.draw(c, w, h, lv);
		}
		c.restore();
		stroke.setColor((Integer) ARGB.evaluate(fade, 0x1FFFFFFF, (fx.tint & 0xFFFFFF) | 0xB0000000));
		float inset = stroke.getStrokeWidth() / 2f;
		c.drawRoundRect(inset, inset, w - inset, h - inset, dp * 24f, dp * 24f, stroke);
	}
}
