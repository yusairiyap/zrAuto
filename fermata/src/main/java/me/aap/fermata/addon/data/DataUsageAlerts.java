package me.aap.fermata.addon.data;

import static me.aap.fermata.addon.data.DataUsageTracker.LEVEL_LIMIT;
import static me.aap.fermata.addon.data.DataUsageTracker.LEVEL_OK;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.ActivityListener;

/**
 * The data warning and limit alerts on the app's own screen -- the phone's and Android Auto's
 * alike (a toast only ever shows on the phone):
 * <ul>
 * <li>a banner at the top of every tab while usage is past the warning level or the limit,
 * sliding in with a pulse when a level is just reached; it sits right under the title bar, or at
 * the very top over fullscreen video, where it hides itself again after a few seconds. Closing
 * it hides it until the next tab is opened;</li>
 * <li>when playback was paused for the limit, a cover over the video with Tap to continue, which
 * carries on past the limit for the rest of the cycle (the banner offers Continue too).</li>
 * </ul>
 */
public final class DataUsageAlerts implements DataUsageTracker.AlertListener, ActivityListener,
		PreferenceStore.Listener {
	private static final long VIDEO_BANNER_MS = 6000;
	private final MainActivityDelegate activity;
	private final Runnable usageListener = this::update;
	private final Runnable autoHide = () -> hideBanner(true);
	private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateGate;
	@Nullable
	private View banner;
	@Nullable
	private View gate;
	@Nullable
	private ViewGroup root;
	// The level the banner was closed at; it stays closed until another tab or a higher level.
	private int dismissedLevel = LEVEL_OK;
	private int shownLevel = LEVEL_OK;
	private boolean bannerShown;

	private DataUsageAlerts(MainActivityDelegate activity) {
		this.activity = activity;
	}

	public static DataUsageAlerts attach(MainActivityDelegate a) {
		DataUsageAlerts al = new DataUsageAlerts(a);
		DataUsageTracker t = DataUsageTracker.get();
		t.addAlertListener(al);
		t.addListener(al.usageListener);
		DataUsageTracker.prefs().addBroadcastListener(al);
		a.addBroadcastListener(al);
		a.postDelayed(al::update, 1000);
		return al;
	}

	public void detach() {
		DataUsageTracker t = DataUsageTracker.get();
		t.removeAlertListener(this);
		t.removeListener(usageListener);
		DataUsageTracker.prefs().removeBroadcastListener(this);
		activity.removeBroadcastListener(this);
		if (banner != null) banner.removeCallbacks(autoHide);
		detachView(banner);
		detachView(gate);
		if (root != null) root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
		banner = gate = null;
		root = null;
	}

	@Override
	public void onActivityEvent(ActivityDelegate a, long e) {
		if (e == FRAGMENT_CHANGED) {
			// "Every tab": a banner closed on one tab shows again on the next.
			dismissedLevel = LEVEL_OK;
			update();
		} else if (e == ACTIVITY_DESTROY) {
			detach();
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		// The limit or warning may have been changed in Settings.
		if (prefs.contains(DataUsageTracker.LIMIT_GB) || prefs.contains(DataUsageTracker.WARNING_GB) ||
				prefs.contains(DataUsageTracker.CYCLE) || prefs.contains(DataUsageTracker.NETWORKS) ||
				prefs.contains(DataUsageTracker.CYCLE_START)) {
			update();
		}
	}

	@Override
	public void onDataAlert(int level, boolean crossed, boolean paused) {
		if (crossed || paused) dismissedLevel = LEVEL_OK;
		updateBanner(crossed || paused);
		updateGate();
	}

	private void update() {
		updateBanner(false);
		updateGate();
	}

	// ---------------------------------------------------------------------------------------------
	// Banner
	// ---------------------------------------------------------------------------------------------

	private void updateBanner(boolean emphasize) {
		DataUsageTracker t = DataUsageTracker.get();
		int level = t.getLevel();

		// Not on the Data Usage tab (it shows all of this itself) nor in Settings (where it would
		// only cover the very options it's about): on every other tab.
		int active = activity.getActiveFragmentId();
		boolean ownScreen = (active == R.id.data_usage_addon) || (active == R.id.settings_fragment);

		if ((level == LEVEL_OK) || (level <= dismissedLevel) || ownScreen) {
			hideBanner(true);
			return;
		}

		ViewGroup r = root();
		if (r == null) return;
		Context ctx = activity.getContext();
		View b = banner;
		if ((b == null) || (b.getParent() != r)) {
			detachView(b);
			b = banner = createBanner(ctx, r);
		}

		boolean limit = level == LEVEL_LIMIT;
		boolean paused = t.isLimitPaused();
		int bg = ContextCompat.getColor(ctx, limit ? R.color.data_usage_limit : R.color.data_usage_warning);
		int fg = limit ? 0xFFFFFFFF : 0xFF1A1A1A;
		b.setBackgroundTintList(ColorStateList.valueOf(bg));
		ImageView icon = b.findViewById(R.id.data_usage_banner_icon);
		icon.setImageTintList(ColorStateList.valueOf(bg));
		icon.setBackgroundTintList(ColorStateList.valueOf(fg));
		TextView title = b.findViewById(R.id.data_usage_banner_title);
		title.setTextColor(fg);
		title.setText(limit ? R.string.data_usage_status_limit : R.string.data_usage_status_warning);
		TextView text = b.findViewById(R.id.data_usage_banner_text);
		text.setTextColor(fg);
		text.setAlpha(0.85f);
		text.setText(bannerText(ctx, t, limit, paused));
		TextView action = b.findViewById(R.id.data_usage_banner_action);
		action.setBackgroundTintList(ColorStateList.valueOf(fg));
		action.setTextColor(bg);
		if (paused) {
			action.setText(R.string.data_usage_continue);
			action.setVisibility(View.VISIBLE);
			action.setOnClickListener(v -> t.allowOverLimit());
		} else {
			action.setText(R.string.data_usage_details);
			action.setVisibility(View.VISIBLE);
			action.setOnClickListener(v -> activity.showFragment(R.id.data_usage_addon));
		}
		ImageButton close = b.findViewById(R.id.data_usage_banner_close);
		close.setImageTintList(ColorStateList.valueOf(fg));

		boolean levelChanged = level != shownLevel;
		shownLevel = level;
		showBanner(b, emphasize || levelChanged);
	}

	private static String bannerText(Context ctx, DataUsageTracker t, boolean limit, boolean paused) {
		long used = DataUsageStore.sum(t.getCycleUsage());
		long lim = DataUsageTracker.getLimit();
		String u = Formatter.formatShortFileSize(ctx, used);
		String s = (lim > 0) ? ctx.getString(R.string.data_usage_banner_of, u,
				Formatter.formatShortFileSize(ctx, lim)) : ctx.getString(R.string.data_usage_banner_used, u);
		if (paused) s += " · " + ctx.getString(R.string.data_usage_banner_paused);
		else if (limit && DataUsageTracker.isOverLimitAllowed()) {
			s += " · " + ctx.getString(R.string.data_usage_banner_over);
		}
		return s;
	}

	private View createBanner(Context ctx, ViewGroup r) {
		View b = LayoutInflater.from(ctx).inflate(R.layout.data_usage_banner, r, false);
		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
				ConstraintLayout.LayoutParams.MATCH_CONSTRAINT, ConstraintLayout.LayoutParams.WRAP_CONTENT);
		lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		// Under the title bar; at the very top when it's hidden (fullscreen video), as the
		// constraint to a gone view collapses to that view's own top.
		lp.topToBottom = R.id.tool_bar;
		lp.matchConstraintMaxWidth = toIntPx(ctx, 600);
		int m = toIntPx(ctx, 8);
		lp.setMargins(m, m, m, 0);
		b.setLayoutParams(lp);
		b.setElevation(toIntPx(ctx, 24));
		b.setVisibility(View.GONE);
		ImageButton close = b.findViewById(R.id.data_usage_banner_close);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setOnClickListener(v -> {
			dismissedLevel = shownLevel;
			hideBanner(true);
		});
		r.addView(b);
		return b;
	}

	private void showBanner(View b, boolean emphasize) {
		b.removeCallbacks(autoHide);
		// Over fullscreen video, only for a few seconds: the Tap to continue cover, if needed, stays.
		if (isVideoShown()) b.postDelayed(autoHide, VIDEO_BANNER_MS);

		if (bannerShown && (b.getVisibility() == View.VISIBLE)) {
			if (emphasize) pulse(b);
			return;
		}

		bannerShown = true;
		b.animate().cancel();
		b.setVisibility(View.VISIBLE);
		b.setAlpha(0f);
		b.setTranslationY(-toIntPx(b.getContext(), 48));
		b.setScaleX(0.96f);
		b.setScaleY(0.96f);
		b.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
				.setInterpolator(new OvershootInterpolator(1.4f))
				.withEndAction(() -> {
					if (emphasize) pulse(b);
				}).start();
	}

	private void hideBanner(boolean animate) {
		View b = banner;
		bannerShown = false;
		if ((b == null) || (b.getVisibility() != View.VISIBLE)) return;
		b.removeCallbacks(autoHide);
		b.animate().cancel();
		if (!animate) {
			b.setVisibility(View.GONE);
			return;
		}
		b.animate().alpha(0f).translationY(-toIntPx(b.getContext(), 32)).setDuration(220)
				.setInterpolator(new DecelerateInterpolator())
				.withEndAction(() -> b.setVisibility(View.GONE)).start();
	}

	/** A level was just reached: the icon bounces a few times to catch the eye. */
	private static void pulse(View b) {
		View icon = b.findViewById(R.id.data_usage_banner_icon);
		bounce(icon, 3);
	}

	private static void bounce(View v, int times) {
		if (times <= 0) return;
		v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(160)
				.setInterpolator(new DecelerateInterpolator()).withEndAction(() ->
						v.animate().scaleX(1f).scaleY(1f).setDuration(220)
								.setInterpolator(new OvershootInterpolator(3f))
								.withEndAction(() -> bounce(v, times - 1)).start()).start();
	}

	// ---------------------------------------------------------------------------------------------
	// Tap to continue
	// ---------------------------------------------------------------------------------------------

	private void updateGate() {
		DataUsageTracker t = DataUsageTracker.get();
		boolean show = t.isLimitPaused() && isVideoShown();
		View g = gate;

		if (!show) {
			if ((g != null) && (g.getVisibility() == View.VISIBLE)) {
				View hide = g;
				hide.animate().cancel();
				hide.animate().alpha(0f).setDuration(200)
						.withEndAction(() -> hide.setVisibility(View.GONE)).start();
			}
			return;
		}

		ViewGroup r = root();
		if (r == null) return;
		Context ctx = activity.getContext();
		if ((g == null) || (g.getParent() != r)) {
			detachView(g);
			g = gate = createGate(ctx, r);
		}

		long lim = DataUsageTracker.getLimit();
		((TextView) g.findViewById(R.id.data_usage_gate_text)).setText(ctx.getString(
				R.string.data_usage_gate_text,
				Formatter.formatShortFileSize(ctx, DataUsageStore.sum(t.getCycleUsage())),
				Formatter.formatShortFileSize(ctx, lim)));
		if (g.getVisibility() == View.VISIBLE) return;

		g.animate().cancel();
		g.setVisibility(View.VISIBLE);
		g.setAlpha(0f);
		g.animate().alpha(1f).setDuration(250).start();
		View content = g.findViewById(R.id.data_usage_gate_content);
		content.setScaleX(0.85f);
		content.setScaleY(0.85f);
		content.animate().scaleX(1f).scaleY(1f).setDuration(450)
				.setInterpolator(new OvershootInterpolator(1.6f)).start();
		bounce(g.findViewById(R.id.data_usage_gate_icon), 2);
	}

	private View createGate(Context ctx, ViewGroup r) {
		View g = LayoutInflater.from(ctx).inflate(R.layout.data_usage_gate, r, false);
		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
				ConstraintLayout.LayoutParams.MATCH_CONSTRAINT, ConstraintLayout.LayoutParams.MATCH_CONSTRAINT);
		// Over the body, where the video is.
		lp.startToStart = R.id.body_layout;
		lp.endToEnd = R.id.body_layout;
		lp.topToTop = R.id.body_layout;
		lp.bottomToBottom = R.id.body_layout;
		g.setLayoutParams(lp);
		g.setElevation(toIntPx(ctx, 20));
		g.setVisibility(View.GONE);
		g.setOnClickListener(v -> DataUsageTracker.get().allowOverLimit());
		g.findViewById(R.id.data_usage_gate_button)
				.setOnClickListener(v -> DataUsageTracker.get().allowOverLimit());
		// Below the banner, whichever was added first.
		int idx = (banner != null) ? r.indexOfChild(banner) : -1;
		if (idx >= 0) r.addView(g, idx);
		else r.addView(g);
		return g;
	}

	/** Whether a video is showing: fullscreen, or next to the list. */
	private boolean isVideoShown() {
		BodyLayout body = activity.getBody();
		return (body != null) && (body.isVideoMode() || body.isBothMode());
	}

	// ---------------------------------------------------------------------------------------------

	@Nullable
	private ViewGroup root() {
		View v = activity.findViewById(R.id.main_activity);
		if (!(v instanceof ConstraintLayout r)) return null;
		if (root != r) {
			if (root != null) root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
			root = r;
			// Leaving the video (or coming back to it) re-lays out the screen: the cover follows.
			r.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
		}
		return r;
	}

	private static void detachView(@Nullable View v) {
		if ((v != null) && (v.getParent() instanceof ViewGroup p)) p.removeView(v);
	}
}
