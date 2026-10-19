package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.addon.power.PowerSaver;

/**
 * The weather behind the Music tab's cover: whatever ambience is on (rain, lightning, embers, ...)
 * drawn by an {@link AmbienceScene} over the tab's background and under its content, fading in and
 * out with the sound. Takes no touches. It draws (and wakes the screen's drawing) only while the
 * tab is on screen and some sound is audible, and not at all when "Animate the Music tab" is off
 * in the addon's settings or the power saver is on.
 */
public final class AmbienceBackdropView extends View {
	private final AmbienceEngine engine = AmbienceEngine.get();
	private final AmbienceScene scene;
	private final float[] lv = new float[AmbienceEffect.values().length];
	private final AmbienceScene.Clock clock = new AmbienceScene.Clock(this, this::onFrame);
	private final Runnable changed = this::wake;
	private final AmbienceEngine.FlashListener flashListener = s -> {
		if (enabled()) {
			this.scene.flash(s);
			clock.start();
		}
	};
	private boolean attached;

	public AmbienceBackdropView(Context ctx) {
		super(ctx);
		scene = new AmbienceScene(ctx.getResources().getDisplayMetrics().density, 7);
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	private static boolean enabled() {
		return AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.ANIM_MUSIC) &&
				!PowerSaver.get().isActive();
	}

	private void wake() {
		if (attached && (getVisibility() == VISIBLE) && isShown()) clock.start();
		else invalidate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		attached = true;
		engine.addListener(changed);
		engine.addFlashListener(flashListener);
		wake();
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
		if (!enabled()) {
			java.util.Arrays.fill(lv, 0f);
			invalidate();
			return false;
		}
		for (AmbienceEffect e : AmbienceEffect.values()) lv[e.ordinal()] = engine.getVisual(e);
		boolean active = scene.isActive(lv);
		if (active) scene.step(dt, lv);
		// One more draw when it just went quiet: the last frame drew nothing.
		invalidate();
		return active;
	}

	@Override
	protected void onDraw(Canvas c) {
		if (!enabled()) return;
		scene.draw(c, getWidth(), getHeight(), lv);
	}
}
