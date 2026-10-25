package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.menu.OverlayMenuItemView;
import me.aap.utils.ui.menu.OverlayMenuView;
import me.aap.utils.ui.view.DialogView;

/**
 * The look of every menu (set as {@link OverlayMenuView#setStyler} in MainActivityDelegate#init): one
 * rounded card in the Music palette, or glass with the UI style's glass on menus ({@link Glass#onMenus},
 * the tab content blurred behind it); a left-aligned bold header; roomy rows with a rounded highlight
 * for the pressed or car-selected one and the icons in the accent. Dialogs (a {@link DialogView} in the
 * menu) keep their own look (DialogStyle), which uses {@link #panel} for the same card.
 * <p>
 * <b>Where it opens</b>: the context menu (long press on a list item, a Home row, a YouTube thumbnail)
 * opens at the finger (beside and below it, flipped to stay on screen), growing out of the touch point,
 * when a touch on the tab just happened ({@link #recordTouch}, from BodyLayout). On the car screen, for a
 * dialog, and when the keys opened it, it stays in the middle as before.
 */
public final class MenuStyle implements OverlayMenuView.Styler {
	/** How long after a touch the menu still counts as opened by it (a long press is ~0.5 s). */
	private static final long TOUCH_MS = 1500;
	private static long touchAt;
	private static float touchX;
	private static float touchY;

	/** The finger's last press on the tab, in screen coordinates. Called from BodyLayout. */
	public static void recordTouch(MotionEvent e) {
		if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return;
		touchAt = SystemClock.uptimeMillis();
		touchX = e.getRawX();
		touchY = e.getRawY();
	}

	@Override
	public void onShow(OverlayMenuView menu, ViewGroup content) {
		Context ctx = menu.getContext();
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(ctx).peek();
		boolean car = (a != null) && a.isCarActivity();
		boolean dialog = hasDialog(content);
		if (!dialog) {
			SettingsStyle.Colors c = new SettingsStyle.Colors(ctx);
			int panel = panelColor(ctx);
			float radius = c.dp(car ? 20 : 24);
			menu.setBackground(panel(menu, panel, radius));
			menu.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
					o.setAlpha(Glass.onMenus() ? 0.3f : 0.6f);
				}
			});
			menu.setClipToOutline(true);
			styleContent(content, c, car);
		}
		place(menu, car, dialog);
	}

	@Override
	public void onHide(OverlayMenuView menu) {
		// Fades out where it is; the next show decides its place again.
		menu.setTag(R.id.menu_anchor, null);
	}

	private static boolean hasDialog(ViewGroup g) {
		for (int i = 0, n = g.getChildCount(); i < n; i++) {
			if (g.getChildAt(i) instanceof DialogView) return true;
		}
		return false;
	}

	/** The menus' and dialogs' card colour (the Music palette's panel). */
	static int panelColor(Context ctx) {
		Context pc = EffectsUi.palette(ctx);
		TypedValue tv = new TypedValue();
		pc.getTheme().resolveAttribute(R.attr.musicPanelFill, tv, true);
		return tv.data;
	}

	/** A menu or dialog card: glass when the UI style puts glass on menus, else a plain rounded fill. */
	public static Drawable panel(View view, int color, float radius) {
		return new Panel(view, color, radius);
	}

	// ---------------------------------------------------------------------------------------------
	// The rows

	private static void styleContent(ViewGroup content, SettingsStyle.Colors c, boolean car) {
		int p = c.dp(8);
		content.setPadding(p, p, p, p);
		content.setMinimumWidth(c.dp(car ? 300 : 248));
		float scale = textScale(content.getContext());
		for (int i = 0, n = content.getChildCount(); i < n; i++) {
			View v = content.getChildAt(i);
			if (v.getId() == me.aap.utils.R.id.overlay_menu_title) {
				styleTitle((TextView) v, c);
			} else if (v instanceof OverlayMenuItemView item) {
				styleItem(item, c, scale);
			}
		}
	}

	private static float textScale(Context ctx) {
		try {
			return me.aap.utils.ui.activity.ActivityDelegate.get(ctx).getTextIconSize();
		} catch (Throwable ex) {
			return 1f;
		}
	}

	private static void styleTitle(TextView t, SettingsStyle.Colors c) {
		t.setBackground(null);
		t.setElevation(0f);
		t.setTranslationZ(0f);
		t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		t.setTextColor(c.textPrimary);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
		t.setMaxLines(2);
		t.setEllipsize(TextUtils.TruncateAt.END);
		t.setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(12));
	}

	private static void styleItem(OverlayMenuItemView item, SettingsStyle.Colors c, float scale) {
		int h = c.dp(16);
		int v = Math.round(c.dp(12) * Math.max(0.8f, scale));
		item.setPadding(h, v, h, v);
		item.setCompoundDrawablePadding(c.dp(16));
		item.setMinHeight(c.dp(48));
		item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
		item.setTextColor(c.textPrimary);
		TextViewCompat.setCompoundDrawableTintList(item, ColorStateList.valueOf(c.accent));
		float r = c.dp(14);
		StateListDrawable states = new StateListDrawable();
		// The row the car keys (or a d-pad) are on, and a selected one: a rounded accent outline.
		states.addState(new int[]{android.R.attr.state_focused}, row(c, r, true));
		states.addState(new int[]{android.R.attr.state_selected}, row(c, r, true));
		states.addState(new int[]{}, new GradientDrawable());
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(0xFF000000);
		mask.setCornerRadius(r);
		item.setBackground(new RippleDrawable(ColorStateList.valueOf(c.ripple), states, mask));
	}

	private static GradientDrawable row(SettingsStyle.Colors c, float r, boolean on) {
		GradientDrawable d = new GradientDrawable();
		d.setCornerRadius(r);
		d.setColor(on ? (c.accent & 0x00FFFFFF) | 0x2E000000 : 0);
		if (on) d.setStroke(c.dp(2), c.accent);
		return d;
	}

	// ---------------------------------------------------------------------------------------------
	// Where it opens

	private static void place(OverlayMenuView menu, boolean car, boolean dialog) {
		boolean wasAnchored = menu.getTag(R.id.menu_anchored) != null;
		// A submenu of a menu opened at the finger stays where it is, however long the first one was open.
		boolean submenu = menu.getTag(R.id.menu_anchor) != null;
		boolean byTouch = (menu.getId() == R.id.context_menu) && !car && !dialog &&
				(submenu || (SystemClock.uptimeMillis() - touchAt < TOUCH_MS));
		if (!byTouch) {
			menu.setTag(R.id.menu_anchor, null);
			if (wasAnchored) {
				menu.setTag(R.id.menu_anchored, null);
				menu.setTranslationX(0f);
				// A dialog rises into place by its own translation (DialogStyle): left alone.
				if (!dialog) menu.setTranslationY(0f);
			}
			return;
		}
		if (!submenu) menu.setTag(R.id.menu_anchor, new float[]{touchX, touchY});
		menu.setTag(R.id.menu_anchored, Boolean.TRUE);
		if (menu.getTag(R.id.menu_place_listener) == null) {
			View.OnLayoutChangeListener l = (v, left, top, right, bottom, ol, ot, or, ob) -> anchor(menu);
			menu.addOnLayoutChangeListener(l);
			menu.setTag(R.id.menu_place_listener, l);
		}
		anchor(menu);
		if (!submenu) {
			// Grows out of the finger.
			menu.setScaleX(0.86f);
			menu.setScaleY(0.86f);
			menu.animate().scaleX(1f).scaleY(1f).setDuration(220)
					.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		}
	}

	/** Beside and below the finger, flipped left / up where the menu would not fit, never off the screen. */
	private static void anchor(OverlayMenuView menu) {
		if (!(menu.getTag(R.id.menu_anchor) instanceof float[] at)) return;
		if (!(menu.getParent() instanceof View parent)) return;
		int w = menu.getWidth();
		int h = menu.getHeight();
		if ((w == 0) || (h == 0)) return;
		int[] pl = new int[2];
		parent.getLocationOnScreen(pl);
		float d = menu.getResources().getDisplayMetrics().density;
		float ax = at[0] - pl[0];
		float ay = at[1] - pl[1];
		float m = 12 * d;
		float pw = parent.getWidth();
		float ph = parent.getHeight();
		float left = ax + 8 * d;
		if (left + w > pw - m) left = ax - w - 8 * d;
		left = (w > pw - 2 * m) ? (pw - w) / 2f : Math.max(m, Math.min(left, pw - w - m));
		float top = ay;
		if (top + h > ph - m) top = ay - h;
		float topRoom = 24 * d;
		top = (h > ph - m - topRoom) ? (ph - h) / 2f : Math.max(topRoom, Math.min(top, ph - h - m));
		menu.setTranslationX(left - menu.getLeft());
		menu.setTranslationY(top - menu.getTop());
		menu.setPivotX(Math.max(0f, Math.min(w, ax - left)));
		menu.setPivotY(Math.max(0f, Math.min(h, ay - top)));
	}

	// ---------------------------------------------------------------------------------------------
	// The card

	/** A menu's or dialog's card: glass (see {@link Glass}) or a plain rounded fill. */
	private static final class Panel extends Drawable {
		private final View view;
		private final int color;
		private final float radius;
		private final boolean light;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final GlassLens[] lens = new GlassLens[1];
		private int alpha = 255;

		Panel(View view, int color, float radius) {
			this.view = view;
			this.color = color;
			this.radius = radius;
			light = ColorUtils.calculateLuminance(color | 0xFF000000) > 0.5;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			rect.set(getBounds());
			if (rect.isEmpty()) return;
			float a = alpha / 255f;
			if (!Glass.onMenus()) {
				paint.setShader(null);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(color);
				paint.setAlpha(Math.round(((color >>> 24) == 0 ? 255 : (color >>> 24)) * a));
				canvas.drawRoundRect(rect, radius, radius, paint);
				return;
			}
			// Text sits on it: never thinner than 55% over a blur (and 80% over a page or video, where
			// nothing is blurred).
			Glass.Look l = Glass.look().withMinTint(55f);
			boolean blurred = Glass.realBlur() && GlassLens.behindBars(view, canvas, rect, radius, a, l, lens);
			if (!blurred) l = l.withMinTint(80f);
			Glass.paintOver(canvas, rect, radius, color, a, l, light, view.getResources().getDisplayMetrics().density,
					paint, blurred);
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			paint.setColorFilter(colorFilter);
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}

		@Override
		public boolean getOutline(@NonNull Outline outline) {
			outline.setRoundRect(getBounds(), radius);
			return true;
		}
	}
}
