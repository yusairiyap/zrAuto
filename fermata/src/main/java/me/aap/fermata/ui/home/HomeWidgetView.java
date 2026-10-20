package me.aap.fermata.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;

/**
 * The base of every Home widget: a rounded card with no outline that fills itself in one of three
 * looks (solid, glass over the background picture, or in the widget's own colour), clips what it
 * holds, keeps its options, and tells its subclass when it is on screen, how many grid units it
 * has to show things in, and when the look or an option changed.
 * <p>
 * To make a widget: subclass this, build the views in {@link #onCreate} from the helpers below
 * ({@link #text}, {@link #icon}, {@link #iconButton}, {@link #header}: they follow the look), fill
 * them in {@link #onBind}, listen to whatever changes only while {@link #onActive}, lay things out
 * by {@link #isCompact}/{@link #isLarge}/{@link #getCellsW} in {@link #onResized}, and list the
 * settings in {@link #getOptions}. Then register a {@link HomeWidgetType} in {@link HomeWidgets}.
 * Every control that does something must have a click listener (the helpers add one), as car mode
 * finds what the steering wheel can select by those; never put one on the whole card.
 */
public abstract class HomeWidgetView extends FrameLayout {
	public static final int ROLE_PRIMARY = 0;
	public static final int ROLE_SECONDARY = 1;
	public static final int ROLE_TERTIARY = 2;
	public static final int ROLE_ICON = 3;
	public static final int ROLE_ACCENT = 4;
	public static final int ROLE_ON_ACCENT = 5;

	public static final int STYLE_AUTO = 0;
	public static final int STYLE_SOLID = 1;
	public static final int STYLE_GLASS = 2;
	public static final int STYLE_COLOUR = 3;
	public static final String OPT_STYLE = "style";
	public static final String OPT_TITLE = "title";
	/** Corner radius of every card, in dp. */
	public static final float CORNER_DP = 26;

	protected final HomeHost host;
	protected final HomeWidgetType type;
	protected final Context ctx;
	protected final float dp;
	private final FrameLayout content;
	private final GradientDrawable card = new GradientDrawable();
	private final List<Tint> tints = new ArrayList<>();
	private final List<Soft> softs = new ArrayList<>();
	private HomeModel.Item item;
	private int cw = 2;
	private int ch = 2;
	private boolean created;
	private boolean active;
	private boolean hasHeader;
	@Nullable
	private View headerTitle;

	private static final class Tint {
		final View view;
		final int role;
		int now;
		int from;
		int to;

		Tint(View view, int role) {
			this.view = view;
			this.role = role;
		}
	}

	/** A view whose background is the faint fill: {@code radiusDp} rounds it, below 0 a disc. */
	private record Soft(View view, float radiusDp) {}

	protected HomeWidgetView(HomeHost host, HomeWidgetType type) {
		super(host.palette().ctx);
		this.host = host;
		this.type = type;
		this.ctx = getContext();
		this.dp = ctx.getResources().getDisplayMetrics().density;
		setBackground(card);
		final float radius = CORNER_DP * dp;
		setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View v, Outline o) {
				o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
			}
		});
		setClipToOutline(true);
		content = new FrameLayout(ctx);
		int pad = Math.round(14 * dp);
		content.setPadding(pad, pad, pad, pad);
		addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
	}

	// ---------------------------------------------------------------------------------------------
	// What a widget implements

	/** Builds the views, once. {@code content} is the padded area inside the card. */
	protected abstract void onCreate(FrameLayout content);

	/** Fills the views in: after creation, and whenever an option changed. */
	protected void onBind() {
	}

	/** One option changed; the default binds everything again. */
	protected void onOptionChanged(String key) {
		onBind();
	}

	/** The widget now has {@code w} x {@code h} grid units: lay the views out for that. */
	protected void onResized(int w, int h) {
	}

	/** On screen (and Home visible): listen to what changes and draw. */
	protected void onActive() {
	}

	/** Off screen, hidden behind another widget of its stack, or Home is not showing. */
	protected void onInactive() {
	}

	/** The card's look changed: colour whatever the helpers did not. */
	protected void onStyleChanged() {
	}

	/** The widget's own settings, shown in its options panel under the common ones. */
	protected List<HomeOption> getOptions() {
		return new ArrayList<>();
	}

	public static final int GESTURE_H = 1;
	public static final int GESTURE_V = 2;

	/**
	 * The directions in which this widget reads a drag itself (lyrics scrolled, a seek bar): while a
	 * finger is on it, the page does not take such a drag over to scroll. {@link #GESTURE_H},
	 * {@link #GESTURE_V} or both ORed; the stack's swipe is the cell's own business.
	 */
	protected int gestures() {
		return 0;
	}

	final int gesturesNow() {
		return gestures();
	}

	/** What "Same as Home" means for this widget: Home's look, unless it has a look of its own to start from. */
	protected int autoStyle() {
		return host.defaultStyle();
	}

	@StringRes
	protected int autoStyleLabel() {
		return R.string.home_style_auto;
	}

	/** Whether the Look setting is offered: not for a widget that always paints its whole card itself. */
	protected boolean hasLookOption() {
		return !ownBackground();
	}

	/** True for a widget that paints its whole card itself (a picture, a sky): text stays white. */
	protected boolean ownBackground() {
		return false;
	}

	// ---------------------------------------------------------------------------------------------
	// Framework

	final void init(HomeModel.Item item) {
		this.item = item;
		onCreate(content);
		created = true;
		applyStyle(false);
		onResized(cw, ch);
		onBind();
		if (active) onActive();
	}

	public final HomeModel.Item getItem() {
		return item;
	}

	public final HomeWidgetType getType() {
		return type;
	}

	protected final FrameLayout getContent() {
		return content;
	}

	/** Called by the cell when the widget's size in grid units changed. */
	final void setCells(int w, int h) {
		if ((w == cw) && (h == ch)) return;
		cw = w;
		ch = h;
		if (created) onResized(w, h);
	}

	public final int getCellsW() {
		return cw;
	}

	public final int getCellsH() {
		return ch;
	}

	/** Two units or fewer: a strip or a square tile with room for one figure. */
	protected final boolean isCompact() {
		return (cw * ch) <= 2;
	}

	/** Room for the full version: six units or more, or a block of 3 x 3. */
	protected final boolean isLarge() {
		return ((cw * ch) >= 6) || ((cw >= 3) && (ch >= 3));
	}

	private int measuredWpx;
	private int measuredHpx;

	/**
	 * The card's size in pixels, from the measure pass (before it is laid out and drawn), so what
	 * depends on it is already right in the first frame instead of jumping into place after it.
	 */
	protected void onSized(int wPx, int hPx) {
	}

	protected final int widthPx() {
		return measuredWpx;
	}

	protected final int heightPx() {
		return measuredHpx;
	}

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		int w = MeasureSpec.getSize(wSpec);
		int h = MeasureSpec.getSize(hSpec);
		if (created && (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) &&
				(MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY) && ((w != measuredWpx) || (h != measuredHpx))) {
			measuredWpx = w;
			measuredHpx = h;
			onSized(w, h);
		}
		super.onMeasure(wSpec, hSpec);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		setActive(isShown());
	}

	@Override
	protected void onDetachedFromWindow() {
		setActive(false);
		super.onDetachedFromWindow();
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		setActive(visible && isAttachedToWindow());
	}

	private void setActive(boolean a) {
		if (active == a) return;
		active = a;
		if (!created) return;
		if (a) onActive();
		else onInactive();
	}

	protected final boolean isActive() {
		return active;
	}

	// ---------------------------------------------------------------------------------------------
	// Options

	public final int optInt(String key, int def) {
		return item.opts.optInt(key, def);
	}

	public final boolean optBool(String key, boolean def) {
		return item.opts.optBoolean(key, def);
	}

	public final String optString(String key, String def) {
		return item.opts.optString(key, def);
	}

	public final JSONObject opts() {
		return item.opts;
	}

	/** Changes an option, saves it and lets the widget (and the card's look) follow. */
	public final void setOpt(String key, @Nullable Object value) {
		if (item == null) return;
		try {
			if (value == null) item.opts.remove(key);
			else item.opts.put(key, value);
		} catch (org.json.JSONException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to set a widget option");
		}
		host.optionsChanged();
		if (OPT_STYLE.equals(key)) applyStyle();
		else if (OPT_TITLE.equals(key)) applyTitle();
		else onOptionChanged(key);
	}

	/** The common settings (look, title), then the widget's own. */
	final List<HomeOption> allOptions() {
		List<HomeOption> l = new ArrayList<>();
		// A widget that paints its whole card has no look to choose.
		if (hasLookOption()) l.add(HomeOption.choice(OPT_STYLE, ctx.getString(R.string.home_opt_style), 0,
				ctx.getString(autoStyleLabel()), ctx.getString(R.string.home_style_solid),
				ctx.getString(R.string.home_style_glass), ctx.getString(R.string.home_style_colour)));
		if (hasHeader) l.add(HomeOption.sw(OPT_TITLE, ctx.getString(R.string.home_opt_title), true));
		l.addAll(getOptions());
		return l;
	}

	// ---------------------------------------------------------------------------------------------
	// Look

	public final int style() {
		int s = optInt(OPT_STYLE, STYLE_AUTO);
		return (s == STYLE_AUTO) ? autoStyle() : s;
	}

	/** Whether text and icons are white (glass, colour, own background) rather than the theme's. */
	public final boolean lightText() {
		return ownBackground() || (style() != STYLE_SOLID) || !host.palette().light;
	}

	public final int color(int role) {
		HomePalette p = host.palette();
		int s = style();
		boolean white = ownBackground() || (s != STYLE_SOLID);
		switch (role) {
			case ROLE_PRIMARY:
				return white ? 0xFFFFFFFF : p.textPrimary;
			case ROLE_SECONDARY:
				return white ? 0xCCFFFFFF : p.textSecondary;
			case ROLE_TERTIARY:
				return white ? 0x99FFFFFF : p.textTertiary;
			case ROLE_ICON:
				return white ? 0xFFFFFFFF : p.icon;
			case ROLE_ACCENT:
				return (s == STYLE_COLOUR) && !ownBackground() ? 0xFFFFFFFF : type.accent;
			default:
				return (s == STYLE_COLOUR) && !ownBackground() ? HomeUi.shade(type.accent, 0.45f) :
						(HomeUi.isLight(type.accent) ? 0xFF000000 : 0xFFFFFFFF);
		}
	}

	/** A faint fill for chips and tracks inside the card. */
	public final int softFill() {
		return lightText() ? 0x24FFFFFF : host.palette().chipFill;
	}

	public final int ripple() {
		return lightText() ? 0x33FFFFFF : host.palette().chipRipple;
	}

	/** The look Home asks of every widget changed (or this widget's own did). */
	final void restyle() {
		applyStyle();
	}

	private ValueAnimator styleAnim;
	private int cardNow1;
	private int cardNow2;
	private int strokeNow;

	private void applyStyle() {
		applyStyle(created && isShown() && host.animate());
	}

	/** Moves the card and everything tinted to the colours of the look now chosen, gliding when asked. */
	private void applyStyle(boolean animate) {
		int s = style();
		final int c1;
		final int c2;
		final int stroke;
		if (ownBackground()) {
			c1 = c2 = 0xFF14161F;
			stroke = 0;
		} else if (s == STYLE_SOLID) {
			c1 = c2 = host.palette().cardFill | 0xFF000000;
			stroke = 0;
		} else if (s == STYLE_GLASS) {
			c1 = c2 = 0x66101018;
			stroke = 0x22FFFFFF;
		} else {
			c1 = HomeUi.shade(type.accent, 0.8f) | 0xFF000000;
			c2 = HomeUi.shade(type.accent, 0.42f) | 0xFF000000;
			stroke = 0;
		}
		if (styleAnim != null) {
			styleAnim.cancel();
			styleAnim = null;
		}
		final int f1 = cardNow1;
		final int f2 = cardNow2;
		final int fs = strokeNow;
		for (Tint t : tints) {
			t.from = t.now;
			t.to = color(t.role);
		}
		card.setOrientation(GradientDrawable.Orientation.TL_BR);
		card.setCornerRadius(CORNER_DP * dp);
		// What is not a colour glides is set at once: the faint fills, and whatever the widget tints itself.
		for (Soft so : softs) paintSoft(so);
		if (created) onStyleChanged();
		if (!animate || (cardNow1 == 0)) {
			paintStyle(1f, c1, c2, stroke, c1, c2, stroke);
			return;
		}
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(280);
		a.setInterpolator(new android.view.animation.DecelerateInterpolator());
		a.addUpdateListener(v -> paintStyle((float) v.getAnimatedValue(), f1, f2, fs, c1, c2, stroke));
		styleAnim = a;
		a.start();
	}

	private void paintStyle(float f, int f1, int f2, int fs, int c1, int c2, int stroke) {
		cardNow1 = HomeUi.mix(f1, c1, f);
		cardNow2 = HomeUi.mix(f2, c2, f);
		strokeNow = HomeUi.mix(fs, stroke, f);
		card.setColors(new int[]{cardNow1, cardNow2});
		card.setStroke(Math.max(1, Math.round(dp)), strokeNow);
		for (Tint t : tints) {
			t.now = (f >= 1f) ? t.to : HomeUi.mix(t.from, t.to, f);
			paintTint(t);
		}
		invalidate();
	}

	private void retint() {
		for (Tint t : tints) {
			t.now = color(t.role);
			t.from = t.to = t.now;
			paintTint(t);
		}
		for (Soft s : softs) paintSoft(s);
	}

	private void paintSoft(Soft s) {
		if (s.radiusDp < 0) s.view.setBackground(HomeUi.oval(softFill()));
		else s.view.setBackground(HomeUi.pressable(ripple(), softFill(), px(s.radiusDp)));
	}

	/** Gives {@code v} the faint fill of the card's look as its background from now on. */
	protected final void trackSoft(View v, float radiusDp) {
		Soft s = new Soft(v, radiusDp);
		softs.add(s);
		paintSoft(s);
	}

	private void paintTint(Tint t) {
		int c = t.now;
		if (t.view instanceof TextView tv) {
			tv.setTextColor(c);
			tv.setCompoundDrawableTintList(ColorStateList.valueOf(c));
		} else if (t.view instanceof ImageView iv) {
			iv.setImageTintList(ColorStateList.valueOf(c));
		} else if (t.view instanceof ProgressBar pb) {
			pb.setIndeterminateTintList(ColorStateList.valueOf(c));
		}
	}

	/** Stops following the views under {@code g}, which is about to drop them (a list built again). */
	protected final void forget(ViewGroup g) {
		tints.removeIf(t -> isUnder(t.view, g));
		softs.removeIf(t -> isUnder(t.view, g));
	}

	private static boolean isUnder(View v, ViewGroup g) {
		for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
			if (p == g) return true;
		}
		return false;
	}

	/** A small spinner in the card's colour, for what is being loaded. */
	protected final ProgressBar spinner(int sizeDp) {
		ProgressBar pb = new ProgressBar(ctx);
		pb.setIndeterminate(true);
		pb.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
		track(pb, ROLE_SECONDARY);
		return pb;
	}

	// ---------------------------------------------------------------------------------------------
	// Building blocks

	protected final int px(float v) {
		return Math.round(v * dp);
	}

	/** A text view that keeps the card's text colour for {@code role}. */
	protected final TextView text(int role, float sp, boolean bold) {
		TextView t = new TextView(ctx);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setIncludeFontPadding(false);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		track(t, role);
		return t;
	}

	/** An icon that keeps the card's colour for {@code role}. */
	protected final ImageView icon(@DrawableRes int res, int sizeDp, int role) {
		ImageView iv = new ImageView(ctx);
		iv.setImageResource(res);
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		iv.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
		track(iv, role);
		return iv;
	}

	/** Colours {@code v} (a text or image view) by {@code role} from now on. */
	protected final void track(View v, int role) {
		Tint t = new Tint(v, role);
		t.now = t.from = t.to = color(role);
		tints.add(t);
		paintTint(t);
	}

	/** A round icon button; it has a click listener, so car mode can select it. */
	protected final ImageView iconButton(@DrawableRes int res, int sizeDp, int role,
																			 @StringRes int description, Runnable onClick) {
		ImageView b = new ImageView(ctx);
		b.setImageResource(res);
		b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		int pad = px(sizeDp * 0.26f);
		b.setPadding(pad, pad, pad, pad);
		b.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
		b.setBackground(HomeUi.pressable(ripple(), 0, px(sizeDp) / 2f));
		b.setContentDescription(ctx.getString(description));
		b.setClickable(true);
		b.setFocusable(true);
		b.setOnClickListener(v -> onClick.run());
		track(b, role);
		return b;
	}

	/**
	 * The usual top row: the widget's icon in a soft disc, its name, and (when {@code open} is not
	 * null) a button that opens the widget's tab. The name can be turned off in the options.
	 */
	protected final LinearLayout header(@Nullable Runnable open) {
		hasHeader = true;
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		FrameLayout disc = new FrameLayout(ctx);
		trackSoft(disc, -1);
		ImageView ic = icon(type.icon, 16, ROLE_ACCENT);
		FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER);
		disc.addView(ic, ilp);
		row.addView(disc, new LinearLayout.LayoutParams(px(30), px(30)));
		TextView title = text(ROLE_PRIMARY, 14.5f, true);
		title.setText(type.name);
		title.setSingleLine(true);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		tlp.setMarginStart(px(8));
		row.addView(title, tlp);
		headerTitle = title;
		if (open != null) {
			// The icon and the name go where the arrow goes; each is its own part, so car mode selects them.
			disc.setClickable(true);
			disc.setFocusable(true);
			disc.setContentDescription(ctx.getString(type.name));
			disc.setOnClickListener(v -> open.run());
			title.setClickable(true);
			title.setFocusable(true);
			title.setOnClickListener(v -> open.run());
			ImageView b = iconButton(me.aap.utils.R.drawable.chevron_right, 30, ROLE_SECONDARY,
					R.string.home_open, open);
			row.addView(b);
		}
		applyTitle();
		return row;
	}

	private void applyTitle() {
		View t = headerTitle;
		if (t == null) return;
		t.setVisibility(optBool(OPT_TITLE, true) ? VISIBLE : GONE);
	}

	/** Opens this widget's tab: what the header's button does by default. */
	protected final void open(int fragmentId) {
		host.open(fragmentId);
	}

	/**
	 * Marks a control that is held or dragged (a seek bar): a long press on it must not pick the
	 * widget up.
	 */
	protected final void noGrab(View v) {
		v.setTag(R.id.home_no_grab, Boolean.TRUE);
	}

	/** A small pill button with an icon and a label. */
	protected final TextView chip(@DrawableRes int icon, CharSequence label, Runnable onClick) {
		TextView t = new TextView(ctx);
		t.setText(label);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setSingleLine(true);
		t.setGravity(Gravity.CENTER_VERTICAL);
		t.setCompoundDrawablePadding(px(6));
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		t.setPadding(px(12), 0, px(14), 0);
		t.setMinHeight(px(34));
		trackSoft(t, 17);
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> onClick.run());
		track(t, ROLE_PRIMARY);
		return t;
	}

	/** A line saying the addon behind the widget is off. */
	protected final TextView note(@StringRes int res) {
		TextView t = text(ROLE_SECONDARY, 13, false);
		t.setText(res);
		t.setGravity(Gravity.CENTER);
		return t;
	}
}
