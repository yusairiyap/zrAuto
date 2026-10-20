package me.aap.fermata.ui.home.widget;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.ambience.AmbienceAddon;
import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.addon.power.PowerSaver;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeModal;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.AmbienceHeroView;
import me.aap.fermata.ui.view.AmbienceScene;
import me.aap.fermata.ui.view.ChipFlowLayout;

/**
 * The Ambience tab's sky on Home: a gradient that takes the colours of whatever sounds are on, with
 * the same animated weather drawn over it (rain, lightning, embers, waves, fireflies), a start/stop
 * button, a timer, and round shortcuts for the sounds picked in the options. Idle it is a still,
 * quiet gradient and costs nothing.
 */
final class AmbienceWidget extends HomeWidgetView {
	private static final int IDLE_TOP = 0xFF23254F;
	private static final int IDLE_BOTTOM = 0xFF0F1026;
	private static final String DEFAULT_SOUNDS = "rain,thunder,fire,wind";

	private final AmbienceScene scene = new AmbienceScene(getResources().getDisplayMetrics().density, 77);
	private final float[] lv = new float[AmbienceEffect.values().length];
	private final AmbienceScene.Clock clock = new AmbienceScene.Clock(this, this::onFrame);
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable countdown = this::tickCountdown;
	private final Runnable changed = this::sync;
	private final AmbienceEngine.FlashListener flash = s -> {
		if (animated()) {
			scene.flash(s);
			clock.start();
		}
	};
	private LinearGradient sky;
	private int skyTop = IDLE_TOP;
	private int skyBottom = IDLE_BOTTOM;
	private final RectF rect = new RectF();

	private TextView title;
	private TextView subtitle;
	private ImageView action;
	private TextView timer;
	private LinearLayout shortcuts;
	private TextView off;
	private LinearLayout texts;
	/** The sounds the shortcut chips stand for now: when they change, the chips come in with a pop. */
	private final List<AmbienceEffect> chipEffects = new ArrayList<>();
	private final List<ImageView> chipViews = new ArrayList<>();
	private boolean listening;
	private int enableChecks;
	private final Runnable waitForAddon = this::waitForAddon;

	AmbienceWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	/** The sky is the card in the Colour look (and what it is by default); Solid and Glass make it a plain card. */
	@Override
	protected boolean ownBackground() {
		return style() == STYLE_COLOUR;
	}

	@Override
	protected int autoStyle() {
		return STYLE_COLOUR;
	}

	@Override
	protected int autoStyleLabel() {
		return R.string.home_style_sky;
	}

	@Override
	protected boolean hasLookOption() {
		return true;
	}

	@Override
	protected void onStyleChanged() {
		invalidate();
	}

	@Override
	protected void onCreate(FrameLayout content) {
		setWillNotDraw(false);
		texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		title = text(ROLE_PRIMARY, 20, true);
		title.setSingleLine(true);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		subtitle = text(ROLE_SECONDARY, 13, false);
		subtitle.setSingleLine(true);
		texts.addView(title);
		texts.addView(subtitle);
		// The name goes where the sounds are: the Ambience tab.
		texts.setClickable(true);
		texts.setFocusable(true);
		texts.setContentDescription(ctx.getString(R.string.ambience_title));
		texts.setOnClickListener(v -> host.run(Action.AMBIENCE));
		content.addView(texts);

		action = new ImageView(ctx);
		action.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		int p = px(12);
		action.setPadding(p, p, p, p);
		action.setClickable(true);
		action.setFocusable(true);
		action.setOnClickListener(v -> {
			engine().toggleAll();
			v.animate().cancel();
			v.setScaleX(0.88f);
			v.setScaleY(0.88f);
			v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(new OvershootInterpolator(2.4f)).start();
		});
		content.addView(action);

		timer = chip(R.drawable.timer, "", this::stepTimer);
		content.addView(timer);

		shortcuts = new LinearLayout(ctx);
		shortcuts.setOrientation(LinearLayout.HORIZONTAL);
		shortcuts.setGravity(Gravity.CENTER_VERTICAL);
		content.addView(shortcuts);

		off = note(R.string.home_ambience_off);
		off.setVisibility(GONE);
		content.addView(off, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.slider("count", ctx.getString(R.string.home_opt_shortcuts), 0, 6, 4, null));
		l.add(HomeOption.button(ctx.getString(R.string.home_opt_pick_sounds), this::pickSounds));
		l.add(HomeOption.sw("timer", ctx.getString(R.string.home_opt_show_timer), true));
		return l;
	}

	/** The engine, which is only made once the addon is on: its sounds are not free to set up. */
	private static AmbienceEngine engine() {
		return AmbienceEngine.get();
	}

	private boolean animated() {
		return AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.ANIM_TAB) && !PowerSaver.get().isActive()
				&& host.animate();
	}

	@Override
	protected void onBind() {
		layoutFor();
		sync();
	}

	@Override
	protected void onResized(int w, int h) {
		if (texts != null) layoutFor();
	}

	private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
		FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
		p.setMargins(l, t, r, b);
		return p;
	}

	private void layoutFor() {
		boolean strip = getCellsH() == 1;
		int pad = px(2);
		int btn = px(strip ? 46 : 52);
		action.setLayoutParams(lp(btn, btn, Gravity.END | (strip ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM),
				0, 0, pad, strip ? 0 : pad));
		texts.setLayoutParams(lp(-1, -2, Gravity.TOP | Gravity.START, pad, pad, strip ? px(60) : px(120), 0));
		title.setTextSize(strip ? 17 : 21);
		boolean showTimer = !strip && optBool("timer", true) && (getCellsW() >= 3 || getCellsH() >= 2);
		timer.setVisibility(showTimer ? VISIBLE : GONE);
		timer.setLayoutParams(lp(-2, px(34), Gravity.TOP | Gravity.END, 0, pad, pad, 0));
		int max = strip ? 0 : optInt("count", 4);
		shortcuts.setLayoutParams(lp(-2, -2, Gravity.BOTTOM | Gravity.START, pad, 0, px(64), pad));
		fillShortcuts(Math.min(max, (getCellsW() >= 4) ? 6 : getCellsW() >= 3 ? 4 : 3));
	}

	/** The sounds picked in the options, in their order. */
	private List<AmbienceEffect> picked() {
		List<AmbienceEffect> l = new ArrayList<>();
		for (String k : optString("sounds", DEFAULT_SOUNDS).split(",")) {
			for (AmbienceEffect e : AmbienceEffect.values()) {
				if (e.key.equals(k.trim()) && engine().isAvailable(e) && !l.contains(e)) l.add(e);
			}
		}
		return l;
	}

	private void fillShortcuts(int max) {
		if ((max <= 0) || !AmbienceAddon.isEnabled()) {
			shortcuts.removeAllViews();
			chipEffects.clear();
			chipViews.clear();
			return;
		}
		List<AmbienceEffect> want = new ArrayList<>();
		for (AmbienceEffect e : picked()) {
			if (want.size() >= max) break;
			want.add(e);
		}
		if (want.equals(chipEffects) && (chipViews.size() == want.size())) {
			// The same sounds: only which of them are on changed.
			for (int i = 0; i < want.size(); i++) styleChip(chipViews.get(i), want.get(i));
			return;
		}
		shortcuts.removeAllViews();
		chipEffects.clear();
		chipViews.clear();
		boolean pop = host.animate() && isShown();
		int n = 0;
		for (AmbienceEffect e : want) {
			ImageView b = new ImageView(ctx);
			b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
			int pd = px(9);
			b.setPadding(pd, pd, pd, pd);
			b.setImageResource(e.icon);
			b.setContentDescription(ctx.getString(e.name));
			b.setClickable(true);
			b.setFocusable(true);
			b.setOnClickListener(v -> engine().toggle(e));
			styleChip(b, e);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(40), px(40));
			blp.setMarginEnd(px(8));
			shortcuts.addView(b, blp);
			chipEffects.add(e);
			chipViews.add(b);
			if (pop) {
				// Each one pops in after the one before, so a resize or a first show is not a blink.
				b.setAlpha(0f);
				b.setScaleX(0.6f);
				b.setScaleY(0.6f);
				b.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80L + n * 55L).setDuration(300)
						.setInterpolator(new OvershootInterpolator(2f)).start();
			}
			n++;
		}
	}

	private void styleChip(ImageView b, AmbienceEffect e) {
		boolean on = engine().isOn(e);
		b.setImageTintList(android.content.res.ColorStateList.valueOf(on ? 0xFF14152B : e.tint));
		b.setBackground(HomeUi.pressable(0x33FFFFFF, on ? e.tint : 0x33FFFFFF, px(20)));
	}

	private void pickSounds() {
		if (!AmbienceAddon.isEnabled()) return;
		Set<String> chosen = new LinkedHashSet<>();
		for (AmbienceEffect e : picked()) chosen.add(e.key);
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		TextView hint = new TextView(host.palette().ctx);
		hint.setText(R.string.home_pick_sounds_hint);
		hint.setTextColor(host.palette().textSecondary);
		hint.setTextSize(13);
		box.addView(hint);
		ChipFlowLayout flow = new ChipFlowLayout(host.palette().ctx);
		flow.setPadding(0, px(12), 0, px(4));
		box.addView(flow);
		fillPicker(flow, chosen);
		HomeModal.show(host, ctx.getString(R.string.home_opt_pick_sounds), R.drawable.ambience, box, null);
	}

	private void fillPicker(ChipFlowLayout flow, Set<String> chosen) {
		flow.removeAllViews();
		for (AmbienceEffect e : AmbienceEffect.values()) {
			if (!engine().isAvailable(e)) continue;
			boolean on = chosen.contains(e.key);
			flow.addView(ChipFlowLayout.chip(host.palette().ctx, e.icon, ctx.getString(e.name), on, () -> {
				if (!chosen.remove(e.key)) chosen.add(e.key);
				setOpt("sounds", String.join(",", chosen));
				fillPicker(flow, chosen);
				layoutFor();
			}));
		}
	}

	private void stepTimer() {
		if (!AmbienceAddon.isEnabled()) return;
		int[] lens = AmbienceEngine.LENGTHS;
		int cur = engine().getLengthMinutes();
		int at = 0;
		for (int i = 0; i < lens.length; i++) if (lens[i] == cur) at = i;
		engine().setLengthMinutes(lens[(at + 1) % lens.length]);
	}

	// ---------------------------------------------------------------------------------------------

	@Override
	protected void onActive() {
		if (!AmbienceAddon.isEnabled()) {
			// Right after the app starts the addon may not be up yet: ask again for a while.
			sync();
			enableChecks = 0;
			handler.removeCallbacks(waitForAddon);
			handler.postDelayed(waitForAddon, 500);
			return;
		}
		listen();
	}

	private void listen() {
		if (listening) return;
		listening = true;
		engine().addListener(changed);
		engine().addFlashListener(flash);
		sync();
		clock.start();
	}

	private void waitForAddon() {
		if (!isActive() || listening) return;
		if (AmbienceAddon.isEnabled()) {
			listen();
		} else if (++enableChecks < 30) {
			handler.postDelayed(waitForAddon, 500);
		}
	}

	@Override
	protected void onInactive() {
		handler.removeCallbacks(waitForAddon);
		if (!listening) return;
		listening = false;
		engine().removeListener(changed);
		engine().removeFlashListener(flash);
		handler.removeCallbacks(countdown);
		clock.stop();
	}

	private void sync() {
		if (title == null) return;
		boolean enabled = AmbienceAddon.isEnabled();
		off.setVisibility(enabled ? GONE : VISIBLE);
		int vis = enabled ? VISIBLE : GONE;
		texts.setVisibility(vis);
		action.setVisibility(vis);
		shortcuts.setVisibility(vis);
		if (!enabled) {
			timer.setVisibility(GONE);
			return;
		}
		boolean on = engine().anyOn();
		boolean held = engine().isHeld();
		title.setText(on ? engine().describe(ctx) : ctx.getString(R.string.ambience_title));
		subtitle.setText(held ? R.string.ambience_hero_paused : on ? R.string.ambience_hero_playing :
				R.string.ambience_hero_idle);
		action.setImageResource(on ? R.drawable.stop : R.drawable.play);
		int fg = on ? 0xFF14152B : 0xFFFFFFFF;
		action.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
		action.setBackground(HomeUi.pressable(0x33FFFFFF, on ? 0xF2FFFFFF : 0x33FFFFFF, px(30)));
		action.setContentDescription(ctx.getString(on ? R.string.ambience_stop_all : R.string.ambience_start));
		timer.setAlpha(held ? 0.55f : 1f);
		updateTimer();
		fillShortcuts(Math.min(getCellsH() == 1 ? 0 : optInt("count", 4),
				(getCellsW() >= 4) ? 6 : getCellsW() >= 3 ? 4 : 3));
		handler.removeCallbacks(countdown);
		if (isActive() && (engine().getRemainingMs() >= 0)) handler.postDelayed(countdown, 1000);
		if (isActive()) clock.start();
		invalidate();
	}

	private void tickCountdown() {
		updateTimer();
		if (isActive() && (engine().getRemainingMs() >= 0)) handler.postDelayed(countdown, 1000);
	}

	private void updateTimer() {
		long left = engine().getRemainingMs();
		int min = engine().getLengthMinutes();
		String s;
		if (left >= 0) {
			long sec = (left + 999) / 1000;
			String t = (sec >= 3600) ? String.format(Locale.getDefault(), "%d:%02d:%02d", sec / 3600,
					sec / 60 % 60, sec % 60) : String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
			s = ctx.getString(R.string.ambience_left, t);
		} else {
			s = AmbienceHeroView.lengthLabel(ctx, min);
		}
		if (!s.contentEquals(timer.getText())) timer.setText(s);
	}

	// ---------------------------------------------------------------------------------------------
	// The sky

	private boolean onFrame(float dt) {
		if (!AmbienceAddon.isEnabled()) return false;
		float sum = 0f, r = 0f, g = 0f, b = 0f;
		boolean anim = animated();
		for (AmbienceEffect e : AmbienceEffect.values()) {
			float shown = engine().getVisual(e);
			lv[e.ordinal()] = anim ? shown : 0f;
			float f = engine().getFade(e);
			if (f <= 0f) continue;
			sum += f;
			int d = e.deep;
			r += f * ((d >> 16) & 0xFF);
			g += f * ((d >> 8) & 0xFF);
			b += f * (d & 0xFF);
		}
		if (sum > 0.001f) {
			float k = Math.min(1f, sum);
			skyTop = mix(IDLE_TOP, r / sum, g / sum, b / sum, k, 1f);
			skyBottom = mix(IDLE_BOTTOM, r / sum, g / sum, b / sum, k, 0.5f);
		} else {
			skyTop = IDLE_TOP;
			skyBottom = IDLE_BOTTOM;
		}
		sky = null;
		boolean active = scene.isActive(lv) || (sum > 0.001f) || engine().isAnyPlaying();
		if (anim && active) scene.step(dt, lv);
		invalidate();
		return active;
	}

	private static int mix(int idle, float r, float g, float b, float k, float dark) {
		float ir = (idle >> 16) & 0xFF, ig = (idle >> 8) & 0xFF, ib = idle & 0xFF;
		int rr = Math.round(ir + (r * dark - ir) * k);
		int gg = Math.round(ig + (g * dark - ig) * k);
		int bb = Math.round(ib + (b * dark - ib) * k);
		return 0xFF000000 | (clamp(rr) << 16) | (clamp(gg) << 8) | clamp(bb);
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}

	@Override
	protected void onDraw(Canvas c) {
		super.onDraw(c);
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0) || !AmbienceAddon.isEnabled()) return;
		boolean skyCard = ownBackground();
		if (skyCard) {
			if (sky == null) sky = new LinearGradient(0, 0, 0, h, skyTop, skyBottom, Shader.TileMode.CLAMP);
			rect.set(0, 0, w, h);
			paint.setShader(sky);
			c.drawRect(rect, paint);
			paint.setShader(null);
		}
		boolean any = false;
		for (float v : lv) if (v > 0.003f) any = true;
		if (any || scene.isActive(lv)) scene.draw(c, w, h, lv);
		if (skyCard) {
			// A shade along the bottom keeps the shortcuts readable over any weather.
			rect.set(0, 0, w, h);
			paint.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
			c.drawRect(rect, paint);
			paint.setShader(null);
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		sky = null;
		if (texts != null) layoutFor();
	}
}
