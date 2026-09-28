package me.aap.fermata.ui.fragment;

import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_NO_MONTH_DAY;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.DateUtils.formatDateRange;
import static android.text.format.DateUtils.formatDateTime;
import static me.aap.fermata.addon.data.DataUsageStore.CATS;
import static me.aap.fermata.addon.data.DataUsageStore.CAT_MUSIC;
import static me.aap.fermata.addon.data.DataUsageStore.CAT_OTHER;
import static me.aap.fermata.addon.data.DataUsageStore.CAT_VIDEO;
import static me.aap.fermata.addon.data.DataUsageStore.dayKey;
import static me.aap.fermata.addon.data.DataUsageStore.sum;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.data.DataUsageStore;
import me.aap.fermata.addon.data.DataUsageTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.DataUsageChartView;
import me.aap.fermata.ui.view.DataUsageRingView;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;

/**
 * The Data Usage tab: how much internet data the app has used and on what (YouTube videos,
 * YouTube in music mode, everything else), see {@link DataUsageTracker}.
 * <p>
 * At the top, a ring with the usage of the current limit cycle, what's left of the limit and the
 * warning level; below it, a chip to the Data Usage settings (limit, warning, reset, Info Overlay
 * items), then an Overall / Day / Week / Month switch with arrows to step through past periods,
 * a bar chart of the chosen period (hours of a day, days of a week or month, months overall),
 * and the period's breakdown by category.
 * <p>
 * Drawn in the Music tab's palette, dark or light to match the app theme, so the two tabs look
 * like they belong together.
 */
public class DataUsageFragment extends MainActivityFragment implements PreferenceStore.Listener,
		DataUsageTracker.AlertListener {
	private static final int FILTER_OVERALL = 0;
	private static final int FILTER_DAY = 1;
	private static final int FILTER_WEEK = 2;
	private static final int FILTER_MONTH = 3;
	private static final Pref<IntSupplier> FILTER = Pref.i("DATA_USAGE_FILTER", FILTER_DAY);
	private static final int[] CAT_ICONS = {R.drawable.video, R.drawable.music, R.drawable.data_usage};
	private static final int[] CAT_NAMES = {R.string.data_usage_cat_video,
			R.string.data_usage_cat_music, R.string.data_usage_cat_other};

	private final Runnable trackerListener = this::refresh;
	private Context palette;
	private int[] catColors;
	private DataUsageRingView ring;
	private DataUsageChartView chart;
	private TextView cycle;
	private TextView used;
	private TextView usedCaption;
	private TextView status;
	private TextView remaining;
	private TextView limit;
	private TextView warning;
	private TextView period;
	private TextView periodTotal;
	private View prev;
	private View next;
	private TextView footer;
	private final TextView[] filters = new TextView[4];
	@Nullable
	private View filterPill;
	@Nullable
	private ValueAnimator pillAnim;
	private final View[] rows = new View[CATS];
	private int filter;
	// 0 is the current day/week/month/year, -1 the one before, and so on.
	private int offset;
	private boolean listening;

	@Override
	public int getFragmentId() {
		return R.id.data_usage_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.data_usage_title);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		return inflater.cloneInContext(palette).inflate(R.layout.data_usage_fragment, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		Context ctx = requireContext();
		catColors = new int[]{ContextCompat.getColor(ctx, R.color.data_usage_video),
				ContextCompat.getColor(ctx, R.color.data_usage_music),
				ContextCompat.getColor(ctx, R.color.data_usage_other)};

		ring = view.findViewById(R.id.data_usage_ring);
		chart = view.findViewById(R.id.data_usage_chart);
		cycle = view.findViewById(R.id.data_usage_cycle);
		used = view.findViewById(R.id.data_usage_used);
		usedCaption = view.findViewById(R.id.data_usage_used_caption);
		status = view.findViewById(R.id.data_usage_status);
		remaining = view.findViewById(R.id.data_usage_remaining);
		limit = view.findViewById(R.id.data_usage_limit);
		warning = view.findViewById(R.id.data_usage_warning);
		period = view.findViewById(R.id.data_usage_period);
		periodTotal = view.findViewById(R.id.data_usage_period_total);
		prev = view.findViewById(R.id.data_usage_prev);
		next = view.findViewById(R.id.data_usage_next);
		footer = view.findViewById(R.id.data_usage_footer);
		filters[FILTER_OVERALL] = view.findViewById(R.id.data_usage_filter_overall);
		filters[FILTER_DAY] = view.findViewById(R.id.data_usage_filter_day);
		filters[FILTER_WEEK] = view.findViewById(R.id.data_usage_filter_week);
		filters[FILTER_MONTH] = view.findViewById(R.id.data_usage_filter_month);
		rows[CAT_VIDEO] = view.findViewById(R.id.data_usage_row_video);
		rows[CAT_MUSIC] = view.findViewById(R.id.data_usage_row_music);
		rows[CAT_OTHER] = view.findViewById(R.id.data_usage_row_other);

		int track = paletteColor(R.attr.musicSeekTrack);
		ring.setColors(catColors, track, ContextCompat.getColor(ctx, R.color.data_usage_warning),
				ContextCompat.getColor(ctx, R.color.data_usage_limit));
		chart.setColors(catColors, paletteColor(R.attr.musicTextSecondary), track,
				paletteColor(R.attr.musicPlayFill), paletteColor(R.attr.musicPlayIcon));

		for (int cat = 0; cat < CATS; cat++) {
			View r = rows[cat];
			ImageView icon = r.findViewById(R.id.data_usage_row_icon);
			icon.setImageResource(CAT_ICONS[cat]);
			icon.setImageTintList(ColorStateList.valueOf(catColors[cat]));
			icon.setBackgroundTintList(ColorStateList.valueOf(
					ColorUtils.setAlphaComponent(catColors[cat], 0x33)));
			((TextView) r.findViewById(R.id.data_usage_row_title)).setText(CAT_NAMES[cat]);
			LinearProgressIndicator bar = r.findViewById(R.id.data_usage_row_bar);
			bar.setIndicatorColor(catColors[cat]);
			bar.setTrackColor(track);
		}

		filter = Math.max(0, Math.min(3, DataUsageTracker.prefs().getIntPref(FILTER)));
		for (int i = 0; i < filters.length; i++) {
			int f = i;
			filters[i].setOnClickListener(v -> setFilter(f));
		}
		filterPill = view.findViewById(R.id.data_usage_filter_pill);
		// Placed (not animated) once the options have their widths, and again on any relayout.
		filters[0].addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) v.post(() -> moveFilterPill(false));
		});
		prev.setOnClickListener(v -> step(-1));
		next.setOnClickListener(v -> step(1));
		view.findViewById(R.id.data_usage_settings).setOnClickListener(v ->
				a.showFragment(R.id.settings_fragment, SettingsFragment.addonSettings("data_usage")));

		// tool_bar/nav_bar are drawn over the fragment: the list has to keep clear of them itself.
		a.insetScrollableContent(view.findViewById(R.id.data_usage_scroll));

		// Not during layout: switching the columns' layout requests another one.
		view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (((r - l) != (or - ol)) || ((b - t) != (ob - ot))) v.post(() -> updateSplit(v));
		});
	}

	/**
	 * Side by side (the ring and its figures on the left, the period, chart and breakdown on the
	 * right) wherever there's room for two columns: on the car screen, in landscape, and on large
	 * screens in either orientation -- the same rule as the YouTube Up next panel.
	 */
	private void updateSplit(View root) {
		int w = root.getWidth();
		if ((w == 0) || (getContext() == null)) return;
		float dp = w / getResources().getDisplayMetrics().density;
		boolean split = getActivityDelegate().isCarActivity() || (dp >= 720) ||
				((w > root.getHeight()) && (dp >= 560));
		LinearLayout cols = root.findViewById(R.id.data_usage_columns);
		int orientation = split ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
		if ((cols == null) || (cols.getOrientation() == orientation)) return;

		cols.setOrientation(orientation);
		View start = root.findViewById(R.id.data_usage_col_start);
		View end = root.findViewById(R.id.data_usage_col_end);
		LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) start.getLayoutParams();
		LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) end.getLayoutParams();
		slp.width = elp.width = split ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
		slp.weight = elp.weight = split ? 1f : 0f;
		elp.setMarginStart(split ? UiUtils.toIntPx(root.getContext(), 16) : 0);
		start.setLayoutParams(slp);
		end.setLayoutParams(elp);
		// Side by side, the two columns start level with each other.
		View f = root.findViewById(R.id.data_usage_filters);
		ViewGroup.MarginLayoutParams flp = (ViewGroup.MarginLayoutParams) f.getLayoutParams();
		flp.topMargin = split ? 0 : UiUtils.toIntPx(root.getContext(), 18);
		f.setLayoutParams(flp);
		f.post(() -> moveFilterPill(false));
	}

	@Override
	public void onResume() {
		super.onResume();
		updateActive();
	}

	@Override
	public void onPause() {
		super.onPause();
		setListening(false);
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		updateActive();
	}

	@Override
	public void onDestroyView() {
		if (pillAnim != null) pillAnim.cancel();
		pillAnim = null;
		filterPill = null;
		setListening(false);
		super.onDestroyView();
	}

	private void updateActive() {
		boolean active = isResumed() && !isHidden() && (getView() != null);
		setListening(active);
		if (active) {
			DataUsageTracker.get().flush(); // Up to the moment, not up to 10 seconds ago.
			refresh();
		}
	}

	private void setListening(boolean on) {
		if (listening == on) return;
		listening = on;
		if (on) {
			DataUsageTracker.get().addListener(trackerListener);
			DataUsageTracker.get().addAlertListener(this);
			DataUsageTracker.prefs().addBroadcastListener(this);
		} else {
			DataUsageTracker.get().removeListener(trackerListener);
			DataUsageTracker.get().removeAlertListener(this);
			DataUsageTracker.prefs().removeBroadcastListener(this);
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		if (prefs.contains(FILTER)) return;
		refresh();
	}

	@Override
	public void onDataAlert(int level, boolean crossed, boolean paused) {
		refresh();
	}

	private int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private void setFilter(int f) {
		if (f == filter) return;
		filter = f;
		offset = 0;
		DataUsageTracker.prefs().applyIntPref(FILTER, f);
		moveFilterPill(true);
		refresh();
	}

	/**
	 * Slides the selected pill over to the current filter's option (with a little stretch while it
	 * moves), crossfading the options' text colours on the way; or just puts it there.
	 */
	private void moveFilterPill(boolean animate) {
		View pill = filterPill;
		if ((pill == null) || (getView() == null)) return;
		TextView target = filters[filter];
		int w = target.getWidth();
		if (w <= 0) return;
		if (pill.getLayoutParams().width != w) {
			ViewGroup.LayoutParams lp = pill.getLayoutParams();
			lp.width = w;
			pill.setLayoutParams(lp);
		}
		pill.setPivotX(w / 2f);
		float x = target.getLeft();
		int sel = paletteColor(R.attr.musicPlayIcon);
		int text = paletteColor(R.attr.musicTextPrimary);
		if (pillAnim != null) pillAnim.cancel();

		if (!animate || (pill.getVisibility() != View.VISIBLE)) {
			pill.setTranslationX(x);
			pill.setScaleX(1f);
			pill.setVisibility(View.VISIBLE);
			for (int i = 0; i < filters.length; i++) {
				filters[i].setSelected(i == filter);
				filters[i].setTextColor((i == filter) ? sel : text);
			}
			return;
		}

		float fromX = pill.getTranslationX();
		int[] fromColors = new int[filters.length];
		for (int i = 0; i < filters.length; i++) {
			fromColors[i] = filters[i].getCurrentTextColor();
			filters[i].setSelected(i == filter);
		}
		ArgbEvaluator argb = new ArgbEvaluator();
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(320);
		a.setInterpolator(new DecelerateInterpolator(1.5f));
		a.addUpdateListener(v -> {
			float p = (float) v.getAnimatedValue();
			pill.setTranslationX(fromX + (x - fromX) * p);
			// Stretches a little mid-way, like it's being dragged, and settles back.
			pill.setScaleX(1f + 0.12f * (float) Math.sin(Math.PI * p));
			for (int i = 0; i < filters.length; i++) {
				int to = (i == filter) ? sel : text;
				filters[i].setTextColor((int) argb.evaluate(p, fromColors[i], to));
			}
		});
		pillAnim = a;
		a.start();
	}

	private void step(int by) {
		int o = offset + by;
		if (o > 0) return;
		offset = o;
		refresh();
	}

	// ---------------------------------------------------------------------------------------------
	// Content
	// ---------------------------------------------------------------------------------------------

	private void refresh() {
		if ((getView() == null) || (ring == null)) return;
		refreshCycle();
		refreshPeriod();
	}

	/** The ring and the figures for the current limit cycle. */
	private void refreshCycle() {
		Context ctx = requireContext();
		DataUsageTracker t = DataUsageTracker.get();
		long[] usage = t.getCycleUsage();
		long total = sum(usage);
		long lim = DataUsageTracker.getLimit();
		long warn = DataUsageTracker.getWarning();
		Calendar[] c = DataUsageTracker.getCycle();
		Calendar last = (Calendar) c[1].clone();
		last.add(Calendar.DAY_OF_YEAR, -1);
		int cycleType = DataUsageTracker.prefs().getIntPref(DataUsageTracker.CYCLE);

		String range = (cycleType == DataUsageTracker.CYCLE_DAY) ?
				formatDateTime(ctx, c[0].getTimeInMillis(), FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY) :
				formatDateRange(ctx, c[0].getTimeInMillis(), last.getTimeInMillis(),
						FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH);
		cycle.setText(getString(R.string.data_usage_cycle_range, getString(
				(cycleType == DataUsageTracker.CYCLE_DAY) ? R.string.data_usage_today :
						(cycleType == DataUsageTracker.CYCLE_WEEK) ? R.string.data_usage_this_week :
								R.string.data_usage_this_month), range));
		used.setText(size(total));
		usedCaption.setText((lim > 0) ? getString(R.string.data_usage_of, size(lim)) :
				getString(R.string.data_usage_used));
		ring.setData(usage, lim, warn);

		remaining.setText((lim > 0) ? size(Math.max(0, lim - total)) : "–");
		limit.setText((lim > 0) ? size(lim) : getString(R.string.data_usage_off));
		warning.setText((warn > 0) ? size(warn) : getString(R.string.data_usage_off));

		int bg;
		int fg;
		String text;
		boolean paused = t.isLimitPaused();
		if ((lim > 0) && (total >= lim)) {
			bg = ContextCompat.getColor(ctx, R.color.data_usage_limit);
			fg = 0xFFFFFFFF;
			// The banner with Continue doesn't show on this tab: the pill takes its place.
			text = getString(paused ? R.string.data_usage_status_paused :
					R.string.data_usage_status_limit);
		} else if ((warn > 0) && (total >= warn)) {
			bg = ContextCompat.getColor(ctx, R.color.data_usage_warning);
			fg = 0xFF000000;
			text = getString(R.string.data_usage_status_warning);
		} else if (lim > 0) {
			bg = ContextCompat.getColor(ctx, R.color.data_usage_ok);
			fg = 0xFF000000;
			text = getString(R.string.data_usage_status_ok, Math.round(100.0 * total / lim));
		} else {
			bg = paletteColor(R.attr.musicChipFill);
			fg = paletteColor(R.attr.musicTextPrimary);
			text = getString(R.string.data_usage_status_no_limit);
		}
		status.setBackgroundTintList(ColorStateList.valueOf(bg));
		status.setTextColor(fg);
		status.setText(text);
		status.setOnClickListener(paused ? v -> t.allowOverLimit() : null);
		status.setClickable(paused);

		StringBuilder f = new StringBuilder();
		f.append(getString(R.string.data_usage_footer_since, formatDateTime(ctx,
				DataUsageTracker.prefs().getLongPref(DataUsageTracker.SINCE),
				FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_ABBREV_MONTH)));
		f.append(" · ").append(getString(DataUsageTracker.isMobileOnly() ?
				R.string.data_usage_networks_mobile : R.string.data_usage_networks_all));
		f.append('\n').append(getString(R.string.data_usage_footer_note));
		if (!t.isSupported()) f.append('\n').append(getString(R.string.data_usage_unsupported));
		footer.setText(f);
	}

	/** The switch, the chart and the breakdown for the chosen period. */
	private void refreshPeriod() {
		Context ctx = requireContext();
		DataUsageStore store = DataUsageTracker.get().getStore();
		boolean mobile = DataUsageTracker.isMobileOnly();

		Calendar now = Calendar.getInstance();
		Calendar from = startOfDay(now);
		long[][] values;
		String[] labels;
		String[] names;
		int highlight = -1;
		long[] totals;
		long[] played;
		String title;
		String totalText;

		switch (filter) {
			case FILTER_DAY -> {
				from.add(Calendar.DAY_OF_YEAR, offset);
				long day = dayKey(from);
				values = new long[24][];
				labels = new String[24];
				names = new String[24];
				Calendar h = (Calendar) from.clone();
				for (int i = 0; i < 24; i++) {
					h.set(Calendar.HOUR_OF_DAY, i);
					values[i] = store.hour(day * 100 + i, mobile);
					labels[i] = String.valueOf(i);
					names[i] = android.text.format.DateFormat.getTimeFormat(ctx).format(h.getTime());
				}
				if (offset == 0) highlight = now.get(Calendar.HOUR_OF_DAY);
				totals = store.days(day, day, mobile);
				played = store.playTimeDays(day, day);
				title = (offset == 0) ? getString(R.string.data_usage_today) :
						(offset == -1) ? getString(R.string.data_usage_yesterday) :
								formatDateTime(ctx, from.getTimeInMillis(),
										FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_MONTH);
				totalText = getString(R.string.data_usage_period_total, size(sum(totals)));
				if (!store.hasHours(day) && (sum(totals) > 0)) {
					totalText += " · " + getString(R.string.data_usage_no_hours);
				}
			}
			case FILTER_WEEK -> {
				while (from.get(Calendar.DAY_OF_WEEK) != from.getFirstDayOfWeek()) {
					from.add(Calendar.DAY_OF_YEAR, -1);
				}
				from.add(Calendar.WEEK_OF_YEAR, offset);
				values = new long[7][];
				labels = new String[7];
				names = new String[7];
				SimpleDateFormat wd = new SimpleDateFormat("EEE", Locale.getDefault());
				Calendar d = (Calendar) from.clone();
				long todayKey = dayKey(now);
				for (int i = 0; i < 7; i++) {
					long k = dayKey(d);
					values[i] = store.days(k, k, mobile);
					labels[i] = wd.format(d.getTime());
					names[i] = formatDateTime(ctx, d.getTimeInMillis(),
							FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_MONTH);
					if (k == todayKey) highlight = i;
					d.add(Calendar.DAY_OF_YEAR, 1);
				}
				Calendar end = (Calendar) from.clone();
				end.add(Calendar.DAY_OF_YEAR, 6);
				totals = store.days(dayKey(from), dayKey(end), mobile);
				played = store.playTimeDays(dayKey(from), dayKey(end));
				title = (offset == 0) ? getString(R.string.data_usage_this_week) :
						(offset == -1) ? getString(R.string.data_usage_last_week) :
								formatDateRange(ctx, from.getTimeInMillis(), end.getTimeInMillis(),
										FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH);
				totalText = getString(R.string.data_usage_period_total, size(sum(totals)));
			}
			case FILTER_MONTH -> {
				from.set(Calendar.DAY_OF_MONTH, 1);
				from.add(Calendar.MONTH, offset);
				int n = from.getActualMaximum(Calendar.DAY_OF_MONTH);
				values = new long[n][];
				labels = new String[n];
				names = new String[n];
				Calendar d = (Calendar) from.clone();
				long todayKey = dayKey(now);
				for (int i = 0; i < n; i++) {
					long k = dayKey(d);
					values[i] = store.days(k, k, mobile);
					labels[i] = String.valueOf(i + 1);
					names[i] = formatDateTime(ctx, d.getTimeInMillis(),
							FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_MONTH);
					if (k == todayKey) highlight = i;
					d.add(Calendar.DAY_OF_YEAR, 1);
				}
				Calendar end = (Calendar) from.clone();
				end.set(Calendar.DAY_OF_MONTH, n);
				totals = store.days(dayKey(from), dayKey(end), mobile);
				played = store.playTimeDays(dayKey(from), dayKey(end));
				title = (offset == 0) ? getString(R.string.data_usage_this_month) :
						formatDateTime(ctx, from.getTimeInMillis(),
								FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY | FORMAT_SHOW_YEAR);
				totalText = getString(R.string.data_usage_period_total, size(sum(totals)));
			}
			default -> {
				// Overall: the last 12 months as bars (arrows step a year at a time), and the
				// breakdown for everything since counting started.
				from.set(Calendar.DAY_OF_MONTH, 1);
				from.add(Calendar.MONTH, -11 + offset * 12);
				values = new long[12][];
				labels = new String[12];
				names = new String[12];
				SimpleDateFormat mf = new SimpleDateFormat("MMM", Locale.getDefault());
				Calendar m = (Calendar) from.clone();
				for (int i = 0; i < 12; i++) {
					Calendar e = (Calendar) m.clone();
					e.set(Calendar.DAY_OF_MONTH, e.getActualMaximum(Calendar.DAY_OF_MONTH));
					values[i] = store.days(dayKey(m), dayKey(e), mobile);
					labels[i] = mf.format(m.getTime());
					names[i] = formatDateTime(ctx, m.getTimeInMillis(),
							FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY | FORMAT_SHOW_YEAR);
					if ((offset == 0) && (i == 11)) highlight = i;
					m.add(Calendar.MONTH, 1);
				}
				totals = store.total(mobile);
				played = store.playTimeTotal();
				Calendar end = (Calendar) m.clone();
				end.add(Calendar.DAY_OF_YEAR, -1);
				title = (offset == 0) ? getString(R.string.data_usage_last_12_months) :
						formatDateRange(ctx, from.getTimeInMillis(), end.getTimeInMillis(),
								FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY | FORMAT_SHOW_YEAR |
										FORMAT_ABBREV_MONTH);
				totalText = getString(R.string.data_usage_total_since, size(sum(totals)));
			}
		}

		period.setText(title);
		periodTotal.setText(totalText);
		chart.setData(values, labels, names, highlight);
		setEnabled(next, offset < 0);
		long first = store.firstDay();
		setEnabled(prev, (first != 0) && (first < dayKey(from)));
		refreshBreakdown(totals, played);
	}

	private void refreshBreakdown(long[] totals, long[] played) {
		long total = sum(totals);
		for (int cat = 0; cat < CATS; cat++) {
			View r = rows[cat];
			long v = totals[cat];
			((TextView) r.findViewById(R.id.data_usage_row_value)).setText(size(v));
			int permille = (total > 0) ? (int) Math.round(1000.0 * v / total) : 0;
			((TextView) r.findViewById(R.id.data_usage_row_pct)).setText(
					String.format(Locale.getDefault(), "%d%%", Math.round(permille / 10f)));
			LinearProgressIndicator bar = r.findViewById(R.id.data_usage_row_bar);
			bar.setProgressCompat(permille, true);

			// YouTube's categories: how long it was actually played, and what that cost per hour --
			// the figure that shows how much cheaper music mode really is than video.
			TextView time = r.findViewById(R.id.data_usage_row_time);
			long ms = (cat == CAT_OTHER) ? 0 : played[cat];
			if (ms < 60_000) {
				time.setVisibility((cat == CAT_OTHER) ? View.GONE : View.VISIBLE);
				time.setText((cat == CAT_OTHER) ? "" : getString(R.string.data_usage_not_played));
			} else {
				long perHour = Math.round(v * (3_600_000.0 / ms));
				time.setVisibility(View.VISIBLE);
				time.setText(getString(R.string.data_usage_played, duration(ms), size(perHour)));
			}
		}
	}

	/** "2 h 13 min" / "45 min". */
	private String duration(long ms) {
		long min = ms / 60_000;
		long h = min / 60;
		min %= 60;
		return (h > 0) ? getString(R.string.data_usage_hours_minutes, h, min) :
				getString(R.string.data_usage_minutes, min);
	}

	private static void setEnabled(View v, boolean enabled) {
		v.setEnabled(enabled);
		v.setAlpha(enabled ? 1f : 0.3f);
	}

	private static Calendar startOfDay(Calendar c) {
		Calendar d = (Calendar) c.clone();
		d.set(Calendar.HOUR_OF_DAY, 0);
		d.set(Calendar.MINUTE, 0);
		d.set(Calendar.SECOND, 0);
		d.set(Calendar.MILLISECOND, 0);
		return d;
	}

	private String size(long bytes) {
		return Formatter.formatShortFileSize(requireContext(), bytes);
	}
}
