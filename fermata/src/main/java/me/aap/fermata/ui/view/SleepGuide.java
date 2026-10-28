package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import me.aap.fermata.R;
import me.aap.fermata.addon.sleep.SleepPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * The first-visit guide of the Sleep tab: a card over the app (like the playlist picker, which it shares its slide, dimming and place
 * with) of five pages, each with a little animated picture drawn in code (the phone on the bed and the waves it feels, the score
 * filling in, the stages being drawn, the sounds and the clock) under a title and a few words. Swipe or press Next; it opens by itself
 * the first time the tab is visited and again from Settings &gt; Sleep &gt; Help.
 */
public final class SleepGuide {
	private static final int PAGES = 5;
	private static final int[] TITLES = {R.string.sleep_guide_1_title, R.string.sleep_guide_2_title, R.string.sleep_guide_3_title,
			R.string.sleep_guide_4_title, R.string.sleep_guide_5_title};
	private static final int[] BODIES = {R.string.sleep_guide_1_body, R.string.sleep_guide_2_body, R.string.sleep_guide_3_body,
			R.string.sleep_guide_4_body, R.string.sleep_guide_5_body};
	private static SleepGuide open;

	private final Context ctx;
	private final ViewGroup host;
	private final float density;
	private final SettingsStyle.Colors c;
	private FrameLayout overlay;
	private FrameLayout card;
	private Art art;
	private Dots dots;
	private TextView title;
	private TextView body;
	private TextView back;
	private TextView next;
	private int page;
	private boolean dismissing;

	private SleepGuide(Context ctx, ViewGroup host) {
		this.ctx = ctx;
		this.host = host;
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.c = new SettingsStyle.Colors(ctx);
	}

	/** Shows the guide over the whole app (and notes that it has been seen). */
	public static void show(@NonNull MainActivityDelegate a) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) {
			Log.e("No view to show the sleep guide over");
			return;
		}
		SleepPrefs.store().applyBooleanPref(SleepPrefs.GUIDE_DONE, true);
		Context pc = EffectsUi.palette(a.getContext());
		SleepGuide g = new SleepGuide(pc, host);
		open = g;
		g.build(a);
	}

	/** Closes the guide if it is open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		SleepGuide g = open;
		if ((g == null) || (g.overlay == null) || (g.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		g.dismiss();
		return true;
	}

	private int dp(float v) {
		return Math.round(v * density);
	}

	private void build(MainActivityDelegate a) {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x77000000);
		overlay.setOnClickListener(v -> dismiss());
		me.aap.fermata.action.CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);

		art = new Art(ctx);
		root.addView(art, new LinearLayout.LayoutParams(MATCH_PARENT, dp(190)));

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(dp(24), dp(18), dp(24), 0);
		title = text(18.5f, c.textPrimary, true);
		body = text(14, c.textSecondary, false);
		body.setLineSpacing(dp(2), 1f);
		body.setPadding(0, dp(8), 0, 0);
		body.setMinLines(5);
		texts.addView(title);
		texts.addView(body);
		root.addView(texts, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		dots = new Dots(ctx);
		root.addView(dots, new LinearLayout.LayoutParams(MATCH_PARENT, dp(22)));

		LinearLayout buttons = new LinearLayout(ctx);
		buttons.setGravity(Gravity.CENTER_VERTICAL);
		buttons.setPadding(dp(16), dp(4), dp(16), dp(16));
		back = pill(false);
		next = pill(true);
		back.setOnClickListener(v -> go(page - 1));
		next.setOnClickListener(v -> {
			if (page == PAGES - 1) dismiss();
			else go(page + 1);
		});
		buttons.addView(back, new LinearLayout.LayoutParams(0, dp(46), 1f));
		View gap = new View(ctx);
		buttons.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
		buttons.addView(next, new LinearLayout.LayoutParams(0, dp(46), 1.4f));
		root.addView(buttons, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		card = new FrameLayout(ctx);
		card.setClickable(true);
		float radius = dp(28);
		card.setBackground(MenuStyle.panel(card, MenuStyle.panelColor(ctx), radius));
		card.setClipToOutline(true);
		card.setOutlineProvider(new android.view.ViewOutlineProvider() {
			@Override
			public void getOutline(View v, android.graphics.Outline o) {
				o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
			}
		});
		card.setElevation(dp(12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		// Swipe left or right for the next or the previous page.
		GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
				if (e1 == null) return false;
				float dx = e2.getX() - e1.getX();
				if ((Math.abs(dx) < dp(60)) || (Math.abs(dx) < Math.abs(e2.getY() - e1.getY()))) return false;
				go((dx < 0) ? page + 1 : page - 1);
				return true;
			}
		});
		root.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
		art.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev) || true);

		FrameLayout.LayoutParams lp = OverlayCard.params(a, host, dp(190 + 18 + 24 + 8 + 110 + 22 + 66 + 20));
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		showPage(0, false);
		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();
	}

	private TextView text(float sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private TextView pill(boolean primary) {
		TextView t = text(15, primary ? c.onAccent : c.textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setClickable(true);
		t.setFocusable(true);
		GradientDrawable fill = new GradientDrawable();
		fill.setColor(primary ? c.accent : c.tileFill);
		fill.setCornerRadius(dp(23));
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(0xFF000000);
		mask.setCornerRadius(dp(23));
		t.setBackground(new RippleDrawable(ColorStateList.valueOf(c.ripple), fill, mask));
		return t;
	}

	private void go(int to) {
		if ((to < 0) || (to >= PAGES) || (to == page) || dismissing) return;
		showPage(to, true);
	}

	private void showPage(int p, boolean animate) {
		int from = page;
		page = p;
		art.setPage(p, (p >= from) ? 1 : -1);
		dots.setPage(p);
		back.setText((p == 0) ? ctx.getString(R.string.sleep_guide_skip) : ctx.getString(R.string.sleep_guide_back));
		back.setOnClickListener(v -> {
			if (page == 0) dismiss();
			else go(page - 1);
		});
		next.setText((p == PAGES - 1) ? R.string.sleep_guide_done : R.string.sleep_guide_next);
		if (!animate) {
			title.setText(TITLES[p]);
			body.setText(BODIES[p]);
			return;
		}
		float dir = (p >= from) ? 1f : -1f;
		title.animate().alpha(0f).translationX(-dir * dp(24)).setDuration(110).withEndAction(() -> {
			title.setText(TITLES[p]);
			title.setTranslationX(dir * dp(24));
			title.animate().alpha(1f).translationX(0f).setDuration(200).setInterpolator(new DecelerateInterpolator(1.6f)).start();
		}).start();
		body.animate().alpha(0f).translationX(-dir * dp(24)).setDuration(110).withEndAction(() -> {
			body.setText(BODIES[p]);
			body.setTranslationX(dir * dp(24));
			body.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
		}).start();
	}

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		art.stop();
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	private void remove() {
		if (overlay.getParent() instanceof ViewGroup g) g.removeView(overlay);
		if (open == this) open = null;
	}

	// ---------------------------------------------------------------------------------------------

	/** The page dots: the one of the page is a longer pill, and glides when the page changes. */
	private final class Dots extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private float pos;

		Dots(Context ctx) {
			super(ctx);
		}

		void setPage(int p) {
			ValueAnimator va = ValueAnimator.ofFloat(pos, p);
			va.setDuration(260);
			va.setInterpolator(new DecelerateInterpolator(1.6f));
			va.addUpdateListener(a -> {
				pos = (float) a.getAnimatedValue();
				invalidate();
			});
			va.start();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float d = dp(7);
			float gap = dp(8);
			float wide = dp(22);
			float total = (PAGES - 1) * (d + gap) + wide;
			float x = (getWidth() - total) / 2f;
			float y = getHeight() / 2f;
			for (int i = 0; i < PAGES; i++) {
				float near = Math.max(0f, 1f - Math.abs(pos - i));
				float w = d + (wide - d) * near;
				paint.setColor(c.accent);
				paint.setAlpha(Math.round(90 + 165 * near));
				rect.set(x, y - d / 2f, x + w, y + d / 2f);
				canvas.drawRoundRect(rect, d / 2f, d / 2f, paint);
				x += w + gap;
			}
		}
	}

	/** The little picture of a page: drawn in code, looping. */
	private final class Art extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private final RectF rect = new RectF();
		private final float[] starX = new float[28];
		private final float[] starY = new float[28];
		private final float[] starP = new float[28];
		private int page;
		private int dir = 1;
		private float t;
		private float enter = 1f;
		private ValueAnimator loop;
		private ValueAnimator in;

		Art(Context ctx) {
			super(ctx);
			java.util.Random r = new java.util.Random(7);
			for (int i = 0; i < starX.length; i++) {
				starX[i] = r.nextFloat();
				starY[i] = r.nextFloat() * 0.7f;
				starP[i] = r.nextFloat();
			}
			loop = ValueAnimator.ofFloat(0f, 1f);
			loop.setDuration(5200);
			loop.setRepeatCount(ValueAnimator.INFINITE);
			loop.setInterpolator(new LinearInterpolator());
			loop.addUpdateListener(a -> {
				t = (float) a.getAnimatedValue();
				invalidate();
			});
			loop.start();
		}

		void setPage(int p, int d) {
			page = p;
			dir = d;
			if (in != null) in.cancel();
			enter = 0f;
			in = ValueAnimator.ofFloat(0f, 1f);
			in.setDuration(600);
			in.setInterpolator(new DecelerateInterpolator(1.8f));
			in.addUpdateListener(a -> {
				enter = (float) a.getAnimatedValue();
				invalidate();
			});
			in.start();
		}

		void stop() {
			if (loop != null) loop.cancel();
			if (in != null) in.cancel();
		}

		@Override
		protected void onDetachedFromWindow() {
			stop();
			super.onDetachedFromWindow();
		}

		private float sp(float v) {
			return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
		}

		@Override
		protected void onDraw(Canvas canvas) {
			int w = getWidth();
			int h = getHeight();
			paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF141B3D, 0xFF3A2A66, Shader.TileMode.CLAMP));
			paint.setStyle(Paint.Style.FILL);
			canvas.drawRect(0, 0, w, h, paint);
			paint.setShader(null);
			stars(canvas, w, h);
			canvas.save();
			// The scene slides in from the side the page came from.
			canvas.translate(dir * (1f - enter) * dp(60), 0);
			paint.setAlpha(255);
			switch (page) {
				case 0 -> bed(canvas, w, h);
				case 1 -> trace(canvas, w, h);
				case 2 -> score(canvas, w, h);
				case 3 -> stages(canvas, w, h);
				default -> sounds(canvas, w, h);
			}
			canvas.restore();
		}

		private void stars(Canvas canvas, int w, int h) {
			paint.setStyle(Paint.Style.FILL);
			for (int i = 0; i < starX.length; i++) {
				float tw = 0.5f + 0.5f * (float) Math.sin((t + starP[i]) * 2 * Math.PI);
				paint.setColor(0xFFFFFFFF);
				paint.setAlpha(Math.round(60 + 170 * tw));
				canvas.drawCircle(starX[i] * w, starY[i] * h, dp(0.9f + 1.2f * starP[i]) * (0.7f + 0.3f * tw), paint);
			}
			paint.setAlpha(255);
		}

		private void moon(Canvas canvas, float cx, float cy, float r) {
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(0x22FFE9A8);
			canvas.drawCircle(cx, cy, r * 1.7f, paint);
			paint.setColor(0xFFFFE9A8);
			canvas.drawCircle(cx, cy, r, paint);
			paint.setColor(0xFF1E2250);
			canvas.drawCircle(cx + r * 0.45f, cy - r * 0.2f, r * 0.85f, paint);
		}

		/** Page 1: the phone on the bed, and the waves that show it feels you. */
		private void bed(Canvas canvas, int w, int h) {
			moon(canvas, w * 0.84f, h * 0.24f, dp(15));
			float bx = w * 0.18f;
			float by = h * 0.66f;
			// Mattress and pillow.
			paint.setColor(0xFF2B3A78);
			rect.set(bx, by, w - bx, by + dp(34));
			canvas.drawRoundRect(rect, dp(12), dp(12), paint);
			paint.setColor(0xFF4A5CA8);
			rect.set(bx + dp(10), by - dp(12), bx + dp(70), by + dp(6));
			canvas.drawRoundRect(rect, dp(9), dp(9), paint);
			// The phone, with rings spreading from it.
			float px = w * 0.52f;
			float py = by - dp(6);
			for (int i = 0; i < 3; i++) {
				float ph = (t * 1.2f + i / 3f) % 1f;
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(dp(2));
				paint.setColor(0xFF7FB2FF);
				paint.setAlpha(Math.round(190 * (1f - ph)));
				rect.set(px - dp(14) - ph * dp(46), py - dp(10) - ph * dp(26), px + dp(14) + ph * dp(46), py + dp(10) + ph * dp(26));
				canvas.drawOval(rect, paint);
			}
			paint.setStyle(Paint.Style.FILL);
			paint.setAlpha(255);
			paint.setColor(0xFFE8ECFF);
			rect.set(px - dp(14), py - dp(5), px + dp(14), py + dp(5));
			canvas.drawRoundRect(rect, dp(3), dp(3), paint);
			// Z z z drifting up.
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextAlign(Paint.Align.LEFT);
			for (int i = 0; i < 3; i++) {
				float ph = (t * 1.0f + i / 3f) % 1f;
				paint.setColor(0xFFFFFFFF);
				paint.setAlpha(Math.round(220 * Math.min(1f, ph * 4f) * (1f - ph)));
				paint.setTextSize(sp(11 + 6 * ph));
				canvas.drawText("z", bx + dp(40) + ph * dp(26), by - dp(18) - ph * dp(44), paint);
			}
			paint.setAlpha(255);
			paint.setTypeface(Typeface.DEFAULT);
		}

		/** Page 2: movement as a trace that scrolls past, calm when asleep and spiking when awake. */
		private void trace(Canvas canvas, int w, int h) {
			float mid = h * 0.55f;
			float left = dp(24);
			float right = w - dp(24);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(2.5f));
			paint.setStrokeCap(Paint.Cap.ROUND);
			float prevX = left;
			float prevY = mid;
			int steps = 90;
			for (int i = 0; i <= steps; i++) {
				float x = left + (right - left) * i / steps;
				float u = (x - left) / (right - left) + t * 2f;
				// Mostly still, with a burst once in a while.
				float burst = Math.max(0f, (float) Math.sin(u * 2 * Math.PI * 0.5f - 0.6f));
				burst = (float) Math.pow(burst, 6);
				float amp = dp(3) + burst * dp(46);
				float y = mid + (float) (Math.sin(u * 40) * 0.6 + Math.sin(u * 13)) * amp * 0.5f;
				if (i > 0) {
					paint.setColor((burst > 0.15f) ? 0xFFFF8A65 : 0xFF7FB2FF);
					canvas.drawLine(prevX, prevY, x, y, paint);
				}
				prevX = x;
				prevY = y;
			}
			paint.setStyle(Paint.Style.FILL);
			paint.setTextSize(sp(11));
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(0xFF7FB2FF);
			canvas.drawCircle(left + dp(4), h - dp(20), dp(4), paint);
			paint.setColor(0xFFE8ECFF);
			canvas.drawText(ctx.getString(R.string.sleep_guide_asleep), left + dp(14), h - dp(16), paint);
			paint.setColor(0xFFFF8A65);
			canvas.drawCircle(left + dp(104), h - dp(20), dp(4), paint);
			paint.setColor(0xFFE8ECFF);
			canvas.drawText(ctx.getResources().getStringArray(R.array.sleep_stages)[0], left + dp(114), h - dp(16), paint);
		}

		/** Page 3: the score ring filling in, and its four parts. */
		private void score(Canvas canvas, int w, int h) {
			float p = Math.min(1f, Math.max(0f, t * 1.7f));
			p = p * p * (3 - 2 * p);
			float cx = w * 0.28f;
			float cy = h * 0.5f;
			float r = dp(52);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(10));
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setColor(0x33FFFFFF);
			rect.set(cx - r, cy - r, cx + r, cy + r);
			canvas.drawArc(rect, 0, 360, false, paint);
			paint.setColor(0xFF64B5F6);
			canvas.drawArc(rect, -90, 360f * 0.82f * p * enter, false, paint);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(0xFFFFFFFF);
			paint.setTextAlign(Paint.Align.CENTER);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextSize(sp(30));
			canvas.drawText(String.valueOf(Math.round(82 * p * enter)), cx, cy + sp(10), paint);
			paint.setTypeface(Typeface.DEFAULT);
			// The parts.
			int[] colors = {0xFF64B5F6, 0xFFBA68C8, 0xFF4DB6AC, 0xFFFFB74D};
			float[] fr = {1f, 0.66f, 0.85f, 0.7f};
			String[] names = {ctx.getString(R.string.sleep_part_duration), ctx.getString(R.string.sleep_part_regular),
					ctx.getString(R.string.sleep_part_latency), ctx.getString(R.string.sleep_part_interruptions)};
			float left = w * 0.50f;
			float right = w - dp(22);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(sp(11));
			for (int i = 0; i < 4; i++) {
				float y = h * 0.22f + i * dp(36);
				paint.setColor(0xFFE8ECFF);
				canvas.drawText(names[i], left, y, paint);
				paint.setColor(0x33FFFFFF);
				rect.set(left, y + dp(6), right, y + dp(11));
				canvas.drawRoundRect(rect, dp(3), dp(3), paint);
				float pp = Math.min(1f, Math.max(0f, p * 1.4f - i * 0.12f)) * enter;
				paint.setColor(colors[i]);
				rect.set(left, y + dp(6), left + (right - left) * fr[i] * pp, y + dp(11));
				canvas.drawRoundRect(rect, dp(3), dp(3), paint);
			}
		}

		/** Page 4: the night drawn as stages, with a wake-up in the middle. */
		private void stages(Canvas canvas, int w, int h) {
			float p = Math.min(1f, t * 1.5f);
			float left = dp(26);
			float right = w - dp(26);
			float top = h * 0.14f;
			float rowH = (h * 0.72f) / 4f;
			int[] colors = {0xFFFF8A65, 0xFFBA68C8, 0xFF64B5F6, 0xFF3F51B5};
			// Stage per slice of the night: 0 awake, 1 REM, 2 light, 3 deep.
			int[] night = {0, 2, 3, 3, 2, 3, 2, 1, 2, 0, 2, 3, 2, 1, 2, 1, 2, 0};
			int n = night.length;
			canvas.save();
			canvas.clipRect(left, 0, left + (right - left) * p * Math.max(enter, 0.15f), h);
			for (int i = 0; i < n; i++) {
				float x0 = left + (right - left) * i / n;
				float x1 = left + (right - left) * (i + 1) / n;
				float y = top + night[i] * rowH;
				float pulse = (night[i] == 0 && i == 9) ? 1f + 0.12f * (float) Math.sin(t * 12) : 1f;
				paint.setColor(colors[night[i]]);
				rect.set(x0 + dp(1), y + rowH * 0.2f, x1 - dp(1), y + rowH * (0.2f + 0.6f * pulse));
				canvas.drawRoundRect(rect, dp(4), dp(4), paint);
			}
			canvas.restore();
			paint.setTextSize(sp(10.5f));
			paint.setTextAlign(Paint.Align.LEFT);
			String[] names = ctx.getResources().getStringArray(R.array.sleep_stages);
			int[] order = {0, 1, 2, 3};
			for (int i = 0; i < 4; i++) {
				paint.setColor(0xAAE8ECFF);
				canvas.drawText(names[order[i]], dp(4), top + i * rowH + rowH * 0.58f, paint);
			}
		}

		/** Page 5: the sounds and the clock, a night that starts and ends by itself. */
		private void sounds(Canvas canvas, int w, int h) {
			// The clock: its hand goes round from bedtime to the morning.
			float cx = w * 0.27f;
			float cy = h * 0.5f;
			float r = dp(46);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(4));
			paint.setColor(0x55FFFFFF);
			canvas.drawCircle(cx, cy, r, paint);
			paint.setColor(0xFF7FB2FF);
			rect.set(cx - r, cy - r, cx + r, cy + r);
			canvas.drawArc(rect, -90, 360f * t, false, paint);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeWidth(dp(3));
			paint.setColor(0xFFFFFFFF);
			double ang = t * 2 * Math.PI - Math.PI / 2;
			canvas.drawLine(cx, cy, cx + (float) Math.cos(ang) * r * 0.75f, cy + (float) Math.sin(ang) * r * 0.75f, paint);
			paint.setStyle(Paint.Style.FILL);
			canvas.drawCircle(cx, cy, dp(4), paint);
			moon(canvas, cx, cy - r - dp(18), dp(8));
			// The sounds: layered waves that fade out with the timer, and rain.
			float left = w * 0.50f;
			float right = w - dp(22);
			float fade = 1f - t * 0.85f;
			for (int k = 0; k < 3; k++) {
				path.reset();
				float base = h * (0.46f + k * 0.12f);
				for (int i = 0; i <= 40; i++) {
					float x = left + (right - left) * i / 40f;
					float y = base + (float) Math.sin(i * 0.5f + t * 2 * Math.PI * (k + 1) * 0.5f) * dp(7 - k * 1.5f) * fade;
					if (i == 0) path.moveTo(x, y);
					else path.lineTo(x, y);
				}
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(dp(2.2f));
				paint.setColor(0xFF7FB2FF);
				paint.setAlpha(Math.round((200 - k * 50) * Math.max(0.15f, fade)));
				canvas.drawPath(path, paint);
			}
			paint.setAlpha(255);
			paint.setStrokeWidth(dp(1.6f));
			paint.setColor(0xAAB7D3FF);
			for (int i = 0; i < 7; i++) {
				float ph = (t * 3f + i * 0.31f) % 1f;
				float x = left + (right - left) * (i + 0.5f) / 7f;
				float y = h * 0.14f + ph * h * 0.26f;
				paint.setAlpha(Math.round(200 * Math.max(0.2f, fade) * (1f - ph)));
				canvas.drawLine(x, y, x - dp(2), y + dp(9), paint);
			}
			paint.setAlpha(255);
		}
	}
}
