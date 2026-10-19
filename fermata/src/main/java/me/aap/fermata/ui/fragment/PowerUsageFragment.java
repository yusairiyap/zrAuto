package me.aap.fermata.ui.fragment;

import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_NO_MONTH_DAY;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.DateUtils.formatDateRange;
import static android.text.format.DateUtils.formatDateTime;
import static me.aap.fermata.addon.power.PowerUsageStore.CATS;
import static me.aap.fermata.addon.power.PowerUsageStore.CAT_IDLE;
import static me.aap.fermata.addon.power.PowerUsageStore.CAT_MUSIC;
import static me.aap.fermata.addon.power.PowerUsageStore.CAT_VIDEO;
import static me.aap.fermata.addon.power.PowerUsageStore.dayKey;
import static me.aap.fermata.addon.power.PowerUsageStore.sum;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
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
import java.util.Date;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.power.PowerSaver;
import me.aap.fermata.addon.power.PowerUsageStore;
import me.aap.fermata.addon.power.PowerUsageStore.Usage;
import me.aap.fermata.addon.power.PowerUsageTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.DataUsageChartView;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.PowerGaugeView;
import me.aap.fermata.ui.view.PowerLiveGraphView;
import me.aap.fermata.ui.view.SwitchStyle;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The Power Usage tab (see {@link PowerUsageTracker}): at the top a gauge with the battery current
 * as it flows right now (like the Ampere app) with the battery level, temperature and how long it
 * lasts at this rate; a chip to the Power settings; this session's live graph with its lowest,
 * average and highest reading (resettable); and the power saver switch with what it does and more
 * ways to save battery. Then an Overall / Day / Week / Month switch with arrows, a bar chart of the
 * charge drawn in the chosen period (hours of a day, days of a week or month, months overall) and
 * where it went: video, music, or the app just open, and Android Auto against the phone alone.
 * <p>
 * Drawn in the Music tab's palette like the Data Usage tab, which it follows closely.
 */
public class PowerUsageFragment extends MainActivityFragment implements ToolBarChip.Host {
	private static final ToolBarChip.Mediator TOOL_BAR = new ToolBarChip.Mediator(
			R.id.power_usage_toolbar_chip, R.drawable.settings, R.string.power_settings);
	private static final int FILTER_OVERALL = 0;
	private static final int FILTER_DAY = 1;
	private static final int FILTER_WEEK = 2;
	private static final int FILTER_MONTH = 3;
	private static final Pref<IntSupplier> FILTER = Pref.i("POWER_USAGE_FILTER", FILTER_DAY);
	private static final int[] CAT_ICONS = {R.drawable.video, R.drawable.music, R.drawable.clock};
	private static final int[] CAT_NAMES = {R.string.power_cat_video, R.string.power_cat_music,
			R.string.power_cat_idle};
	// The period figures need no more than this often while the live ones run each second.
	private static final long PERIOD_EVERY_MS = 5000;

	private final Runnable liveListener = this::refreshLive;
	private final Runnable saverListener = this::refreshSaver;
	private Context palette;
	private int[] catColors;
	private PowerGaugeView gauge;
	private PowerLiveGraphView graph;
	private DataUsageChartView chart;
	private TextView status;
	private TextView loadNote;
	@Nullable
	private CharSequence statusLabel;
	private int statusBg;
	@Nullable
	private ValueAnimator statusAnim;
	private TextView value;
	private TextView watts;
	private TextView level;
	private TextView temp;
	private TextView estimate;
	private TextView estimateCaption;
	private TextView unsupported;
	private TextView sessionSince;
	private TextView minView;
	private TextView avgView;
	private TextView maxView;
	private androidx.appcompat.widget.SwitchCompat saverSwitch;
	private TextView saverText;
	private TextView period;
	private TextView periodTotal;
	private TextView carInsight;
	private TextView charged;
	private TextView footer;
	private View prev;
	private View next;
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
	private boolean settingSwitch;
	private long lastPeriod;

	@Override
	public int getFragmentId() {
		return R.id.power_usage_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.power_title);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return TOOL_BAR;
	}

	@Override
	public void onToolBarChipClick() {
		getActivityDelegate().showFragment(R.id.power_settings_fragment, PowerSettingsFragment.USAGE_FROM_TAB);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		return inflater.cloneInContext(palette).inflate(R.layout.power_usage_fragment, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		Context ctx = requireContext();
		catColors = new int[]{ContextCompat.getColor(ctx, R.color.data_usage_video),
				ContextCompat.getColor(ctx, R.color.data_usage_music),
				ContextCompat.getColor(ctx, R.color.data_usage_other)};

		gauge = view.findViewById(R.id.power_gauge);
		graph = view.findViewById(R.id.power_graph);
		chart = view.findViewById(R.id.power_chart);
		status = view.findViewById(R.id.power_status);
		loadNote = view.findViewById(R.id.power_load_note);
		value = view.findViewById(R.id.power_value);
		watts = view.findViewById(R.id.power_watts);
		level = view.findViewById(R.id.power_level);
		temp = view.findViewById(R.id.power_temp);
		estimate = view.findViewById(R.id.power_estimate);
		estimateCaption = view.findViewById(R.id.power_estimate_caption);
		unsupported = view.findViewById(R.id.power_unsupported);
		sessionSince = view.findViewById(R.id.power_session_since);
		minView = view.findViewById(R.id.power_min);
		avgView = view.findViewById(R.id.power_avg);
		maxView = view.findViewById(R.id.power_max);
		saverSwitch = view.findViewById(R.id.power_saver_switch);
		saverText = view.findViewById(R.id.power_saver_text);
		period = view.findViewById(R.id.power_period);
		periodTotal = view.findViewById(R.id.power_period_total);
		carInsight = view.findViewById(R.id.power_car_insight);
		charged = view.findViewById(R.id.power_charged);
		footer = view.findViewById(R.id.power_footer);
		prev = view.findViewById(R.id.power_prev);
		next = view.findViewById(R.id.power_next);
		filters[FILTER_OVERALL] = view.findViewById(R.id.power_filter_overall);
		filters[FILTER_DAY] = view.findViewById(R.id.power_filter_day);
		filters[FILTER_WEEK] = view.findViewById(R.id.power_filter_week);
		filters[FILTER_MONTH] = view.findViewById(R.id.power_filter_month);
		rows[CAT_VIDEO] = view.findViewById(R.id.power_row_video);
		rows[CAT_MUSIC] = view.findViewById(R.id.power_row_music);
		rows[CAT_IDLE] = view.findViewById(R.id.power_row_idle);

		int track = paletteColor(R.attr.musicSeekTrack);
		int low = ContextCompat.getColor(ctx, R.color.power_low);
		int mid = ContextCompat.getColor(ctx, R.color.power_mid);
		int high = ContextCompat.getColor(ctx, R.color.power_high);
		int charge = ContextCompat.getColor(ctx, R.color.power_charge);
		gauge.setColors(track, low, mid, high, charge);
		graph.setColors(charge, mid, track, paletteColor(R.attr.musicTextSecondary));
		chart.setColors(catColors, paletteColor(R.attr.musicTextSecondary), track,
				paletteColor(R.attr.musicPlayFill), paletteColor(R.attr.musicPlayIcon));
		chart.setValueFormatter(this::mah, 100_000);

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

		SwitchStyle.apply(saverSwitch, palette);
		saverSwitch.setChecked(PowerSaver.get().isActive());
		saverSwitch.setOnCheckedChangeListener((b, on) -> {
			if (settingSwitch || (on == PowerSaver.get().isActive())) return;
			PowerSaver.get().setActive(on);
		});
		view.findViewById(R.id.power_reset_session).setOnClickListener(v -> {
			PowerUsageTracker.get().resetSession();
			TopToast.show(R.drawable.power_usage, R.string.power_reset_session_done);
		});

		filter = Math.max(0, Math.min(3, PowerUsageTracker.prefs().getIntPref(FILTER)));
		for (int i = 0; i < filters.length; i++) {
			int f = i;
			filters[i].setOnClickListener(v -> setFilter(f));
		}
		filterPill = view.findViewById(R.id.power_filter_pill);
		// Placed (not animated) once the options have their widths, and again on any relayout.
		filters[0].addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) v.post(() -> moveFilterPill(false));
		});
		prev.setOnClickListener(v -> step(-1));
		next.setOnClickListener(v -> step(1));
		view.findViewById(R.id.power_settings).setOnClickListener(v -> onToolBarChipClick());
		view.findViewById(R.id.power_saver_settings).setOnClickListener(v ->
				getActivityDelegate().showFragment(R.id.power_settings_fragment, PowerSettingsFragment.SAVER_FROM_TAB));
		updateSettingsChip(view);

		// tool_bar/nav_bar are drawn over the fragment: the list has to keep clear of them itself.
		a.insetScrollableContent(view.findViewById(R.id.power_scroll));

		// Not during layout: switching the columns' layout requests another one.
		view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (((r - l) != (or - ol)) || ((b - t) != (ob - ot))) v.post(() -> {
				updateSplit(v);
				updateSettingsChip(v);
			});
		});
	}

	/** The settings chip: in the title bar in landscape and on the car screen, else in here. */
	private void updateSettingsChip(View root) {
		ToolBarChip.update(this, R.id.power_usage_toolbar_chip,
				root.findViewById(R.id.power_settings_row));
	}

	/**
	 * Side by side (the live figures on the left, the history on the right) wherever there's room
	 * for two columns: on the car screen, in landscape, and on large screens in either orientation.
	 */
	private void updateSplit(View root) {
		int w = root.getWidth();
		if ((w == 0) || (getContext() == null)) return;
		float dp = w / getResources().getDisplayMetrics().density;
		boolean split = getActivityDelegate().isCarActivity() || (dp >= 720) ||
				((w > root.getHeight()) && (dp >= 560));
		LinearLayout cols = root.findViewById(R.id.power_columns);
		int orientation = split ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
		if ((cols == null) || (cols.getOrientation() == orientation)) return;

		cols.setOrientation(orientation);
		View start = root.findViewById(R.id.power_col_start);
		View end = root.findViewById(R.id.power_col_end);
		LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) start.getLayoutParams();
		LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) end.getLayoutParams();
		slp.width = elp.width = split ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
		slp.weight = elp.weight = split ? 1f : 0f;
		elp.setMarginStart(split ? UiUtils.toIntPx(root.getContext(), 16) : 0);
		start.setLayoutParams(slp);
		end.setLayoutParams(elp);
		// Side by side, the two columns start level with each other.
		View f = root.findViewById(R.id.power_filters);
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
		if (statusAnim != null) statusAnim.cancel();
		statusAnim = null;
		statusLabel = null;
		filterPill = null;
		setListening(false);
		super.onDestroyView();
	}

	private void updateActive() {
		boolean active = isResumed() && !isHidden() && (getView() != null);
		setListening(active);
		if (active) {
			PowerUsageTracker.get().flush(); // Up to the moment, not up to a minute ago.
			lastPeriod = 0;
			refreshLive();
			refreshSaver();
		}
	}

	/** While on screen the tracker reads once a second, and this follows; off screen it lets go. */
	private void setListening(boolean on) {
		if (listening == on) return;
		listening = on;
		PowerUsageTracker t = PowerUsageTracker.get();
		if (on) {
			t.addListener(liveListener);
			PowerSaver.get().addListener(saverListener);
		} else {
			t.removeListener(liveListener);
			PowerSaver.get().removeListener(saverListener);
		}
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
		PowerUsageTracker.prefs().applyIntPref(FILTER, f);
		moveFilterPill(true);
		refreshPeriod();
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
		refreshPeriod();
	}

	// ---------------------------------------------------------------------------------------------
	// Live

	private void refreshLive() {
		if ((getView() == null) || (gauge == null)) return;
		Context ctx = requireContext();
		PowerUsageTracker t = PowerUsageTracker.get();

		if (!t.isSupported()) {
			unsupported.setVisibility(View.VISIBLE);
			value.setText(R.string.power_stat_unknown);
			status.setVisibility(View.GONE);
		} else if (!t.hasCurrent()) {
			unsupported.setVisibility(View.GONE);
			value.setText(R.string.power_stat_unknown);
			setStatus(R.string.power_state_waiting, paletteColor(R.attr.musicChipFill),
					paletteColor(R.attr.musicTextPrimary));
		} else {
			unsupported.setVisibility(View.GONE);
			float ma = t.getCurrent();
			float load = t.getLoad();
			gauge.setValue(ma, load);
			value.setText(signed(ma));
			float w = t.getWatts();
			watts.setText((w < 0) ? "" : getString(R.string.power_watts,
					String.format(Locale.getDefault(), "%.1f", w),
					String.format(Locale.getDefault(), "%.2f", t.getVoltageMv() / 1000f)));
			// One judgement for the chip and the ring: how long the battery would last at this draw,
			// against its own size (a tablet's 700 mA is a light load, a phone's is a heavy one).
			if (t.isFull() && t.isPlugged() && (Math.abs(ma) < 150)) {
				setStatus(R.string.power_state_full, ContextCompat.getColor(ctx, R.color.power_charge), 0xFF000000);
			} else if (ma > 5) {
				setStatus(R.string.power_state_charging, ContextCompat.getColor(ctx, R.color.power_charge),
						0xFF000000);
			} else if (ma < -5) {
				setStatus((load < 0.4f) ? R.string.power_state_light :
						(load < 1f) ? R.string.power_state_moderate : R.string.power_state_heavy,
						gauge.severityColor(load), 0xFF000000);
			} else {
				setStatus(R.string.power_state_steady, paletteColor(R.attr.musicChipFill),
						paletteColor(R.attr.musicTextPrimary));
			}
			loadNote.setText(t.isCapacityKnown() ?
					getString(R.string.power_load_note, t.getCapacityMah()) :
					getString(R.string.power_load_note_guess, t.getCapacityMah()));
		}

		level.setText((t.getLevel() >= 0) ? String.format(Locale.getDefault(), "%d%%", t.getLevel()) :
				getString(R.string.power_stat_unknown));
		temp.setText((t.getTemperatureTenths() != Integer.MIN_VALUE) ?
				String.format(Locale.getDefault(), "%.1f°C", t.getTemperatureTenths() / 10f) :
				getString(R.string.power_stat_unknown));
		long est = t.getEstimateMs();
		estimateCaption.setText((est < 0) ? R.string.power_stat_full_in : R.string.power_stat_left);
		estimate.setText((est == -1) ? getString(R.string.power_stat_unknown) : duration(Math.abs(est)));

		graph.setData(t.getGraph());
		sessionSince.setText(getString(R.string.power_session_since,
				android.text.format.DateFormat.getTimeFormat(ctx).format(new Date(t.getSessionStart())),
				mah(t.getSessionDrawn())));
		boolean has = t.hasSession();
		minView.setText(has ? getString(R.string.power_ma, signed(t.getSessionMin())) :
				getString(R.string.power_stat_unknown));
		avgView.setText(has ? getString(R.string.power_ma, signed(t.getSessionAverage())) :
				getString(R.string.power_stat_unknown));
		maxView.setText(has ? getString(R.string.power_ma, signed(t.getSessionMax())) :
				getString(R.string.power_stat_unknown));

		long now = android.os.SystemClock.elapsedRealtime();
		if (now - lastPeriod >= PERIOD_EVERY_MS) {
			lastPeriod = now;
			refreshPeriod();
		}
	}

	/**
	 * The state chip: a new state pops its text in and its colour melts over from the old one (the
	 * colour also drifts a little with the load, which then glides rather than jumps).
	 */
	private void setStatus(int text, int bg, int fg) {
		status.setVisibility(View.VISIBLE);
		CharSequence label = getString(text);
		boolean first = statusLabel == null;
		boolean textChanged = !label.equals(statusLabel);
		int from = statusBg;
		statusLabel = label;
		statusBg = bg;
		status.setTextColor(fg);
		if (statusAnim != null) statusAnim.cancel();
		if (first) {
			status.setBackgroundTintList(ColorStateList.valueOf(bg));
			status.setText(label);
			return;
		}
		if (textChanged) {
			status.setText(label);
			status.animate().cancel();
			status.setScaleX(0.8f);
			status.setScaleY(0.8f);
			status.setAlpha(0.3f);
			status.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360)
					.setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
		}
		ArgbEvaluator argb = new ArgbEvaluator();
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(textChanged ? 520 : 420);
		a.addUpdateListener(v -> status.setBackgroundTintList(ColorStateList.valueOf(
				(int) argb.evaluate((float) v.getAnimatedValue(), from, bg))));
		statusAnim = a;
		a.start();
	}

	private void refreshSaver() {
		if ((getView() == null) || (saverSwitch == null)) return;
		PowerSaver s = PowerSaver.get();
		boolean on = s.isActive();
		if (saverSwitch.isChecked() != on) {
			settingSwitch = true;
			saverSwitch.setChecked(on);
			settingSwitch = false;
		}
		if (!on) {
			saverText.setText(R.string.power_saver_off_text);
			return;
		}
		var p = PowerUsageTracker.prefs();
		StringBuilder sb = new StringBuilder(getString(R.string.power_saver_on_text, getString(s.getReason())));
		if (p.getBooleanPref(PowerSaver.DIM_SCREEN)) {
			sb.append('\n').append(getString(R.string.power_saver_does_brightness,
					Math.max(PowerSaver.MIN_BRIGHTNESS, Math.min(PowerSaver.MAX_BRIGHTNESS,
							p.getIntPref(PowerSaver.BRIGHTNESS)))));
		}
		if (p.getBooleanPref(PowerSaver.CAR_SCREEN)) {
			sb.append('\n').append(getString(R.string.power_saver_does_car_screen));
		}
		if (p.getBooleanPref(PowerSaver.CAP_REFRESH)) {
			sb.append('\n').append(getString(R.string.power_saver_does_refresh));
		}
		if (p.getBooleanPref(PowerSaver.PAUSE_DOWNLOADS)) {
			sb.append('\n').append(getString(R.string.power_saver_does_downloads));
		}
		sb.append('\n').append(getString(R.string.power_saver_does_tracker));
		saverText.setText(sb);
	}

	// ---------------------------------------------------------------------------------------------
	// History

	/** The switch, the chart and the breakdown for the chosen period. */
	private void refreshPeriod() {
		if ((getView() == null) || (chart == null)) return;
		Context ctx = requireContext();
		PowerUsageStore store = PowerUsageTracker.get().getStore();

		Calendar now = Calendar.getInstance();
		Calendar from = startOfDay(now);
		long[][] values;
		String[] labels;
		String[] names;
		int highlight = -1;
		Usage usage;
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
					values[i] = store.hour(day * 100 + i).used;
					labels[i] = String.valueOf(i);
					names[i] = android.text.format.DateFormat.getTimeFormat(ctx).format(h.getTime());
				}
				if (offset == 0) highlight = now.get(Calendar.HOUR_OF_DAY);
				usage = store.days(day, day);
				title = (offset == 0) ? getString(R.string.data_usage_today) :
						(offset == -1) ? getString(R.string.data_usage_yesterday) :
								formatDateTime(ctx, from.getTimeInMillis(),
										FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_MONTH);
				totalText = getString(R.string.data_usage_period_total, mah(usage.totalUsed()));
				if (!store.hasHours(day) && (usage.totalUsed() > 0)) {
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
					values[i] = store.days(k, k).used;
					labels[i] = wd.format(d.getTime());
					names[i] = formatDateTime(ctx, d.getTimeInMillis(),
							FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_MONTH);
					if (k == todayKey) highlight = i;
					d.add(Calendar.DAY_OF_YEAR, 1);
				}
				Calendar end = (Calendar) from.clone();
				end.add(Calendar.DAY_OF_YEAR, 6);
				usage = store.days(dayKey(from), dayKey(end));
				title = (offset == 0) ? getString(R.string.data_usage_this_week) :
						(offset == -1) ? getString(R.string.data_usage_last_week) :
								formatDateRange(ctx, from.getTimeInMillis(), end.getTimeInMillis(),
										FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH);
				totalText = getString(R.string.data_usage_period_total, mah(usage.totalUsed()));
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
					values[i] = store.days(k, k).used;
					labels[i] = String.valueOf(i + 1);
					names[i] = formatDateTime(ctx, d.getTimeInMillis(),
							FORMAT_SHOW_DATE | FORMAT_SHOW_WEEKDAY | FORMAT_ABBREV_MONTH);
					if (k == todayKey) highlight = i;
					d.add(Calendar.DAY_OF_YEAR, 1);
				}
				Calendar end = (Calendar) from.clone();
				end.set(Calendar.DAY_OF_MONTH, n);
				usage = store.days(dayKey(from), dayKey(end));
				title = (offset == 0) ? getString(R.string.data_usage_this_month) :
						formatDateTime(ctx, from.getTimeInMillis(),
								FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY | FORMAT_SHOW_YEAR);
				totalText = getString(R.string.data_usage_period_total, mah(usage.totalUsed()));
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
					values[i] = store.days(dayKey(m), dayKey(e)).used;
					labels[i] = mf.format(m.getTime());
					names[i] = formatDateTime(ctx, m.getTimeInMillis(),
							FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY | FORMAT_SHOW_YEAR);
					if ((offset == 0) && (i == 11)) highlight = i;
					m.add(Calendar.MONTH, 1);
				}
				usage = store.total();
				Calendar end = (Calendar) m.clone();
				end.add(Calendar.DAY_OF_YEAR, -1);
				title = (offset == 0) ? getString(R.string.data_usage_last_12_months) :
						formatDateRange(ctx, from.getTimeInMillis(), end.getTimeInMillis(),
								FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY | FORMAT_SHOW_YEAR |
										FORMAT_ABBREV_MONTH);
				totalText = getString(R.string.data_usage_total_since, mah(usage.totalUsed()));
			}
		}

		period.setText(title);
		periodTotal.setText(totalText);
		chart.setData(values, labels, names, highlight);
		setEnabled(next, offset < 0);
		long first = store.firstDay();
		setEnabled(prev, (first != 0) && (first < dayKey(from)));
		refreshBreakdown(usage);

		footer.setText(getString(R.string.power_footer_since, formatDateTime(ctx,
				PowerUsageTracker.prefs().getLongPref(PowerUsageTracker.SINCE),
				FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_ABBREV_MONTH)) + "\n" +
				getString(R.string.power_footer_note));
	}

	private void refreshBreakdown(Usage u) {
		long total = u.totalUsed();
		for (int cat = 0; cat < CATS; cat++) {
			View r = rows[cat];
			long v = u.used[cat];
			((TextView) r.findViewById(R.id.data_usage_row_value)).setText(mah(v));
			int permille = (total > 0) ? (int) Math.round(1000.0 * v / total) : 0;
			((TextView) r.findViewById(R.id.data_usage_row_pct)).setText(
					String.format(Locale.getDefault(), "%d%%", Math.round(permille / 10f)));
			LinearProgressIndicator bar = r.findViewById(R.id.data_usage_row_bar);
			bar.setProgressCompat(permille, true);

			// How long it was spent in this state, and what that cost on average: the figure that
			// shows what video costs against music, and music against doing nothing.
			TextView time = r.findViewById(R.id.data_usage_row_time);
			long ms = u.time[cat];
			time.setVisibility(View.VISIBLE);
			time.setText((ms < 60_000) ? getString(R.string.power_row_none) :
					getString(R.string.power_row_time, duration(ms), getString(R.string.power_ma,
							String.valueOf(Math.round(avgMa(v, ms))))));
		}

		// Android Auto against the phone alone, once there is enough of each to compare.
		if ((u.carTime >= 300_000) && (u.phoneTime >= 300_000)) {
			carInsight.setVisibility(View.VISIBLE);
			carInsight.setText(getString(R.string.power_car_insight,
					getString(R.string.power_ma, String.valueOf(Math.round(avgMa(u.carUsed, u.carTime)))),
					getString(R.string.power_ma, String.valueOf(Math.round(avgMa(u.phoneUsed, u.phoneTime))))));
		} else {
			carInsight.setVisibility(View.GONE);
		}
		if (u.charged > 0) {
			charged.setVisibility(View.VISIBLE);
			charged.setText(getString(R.string.power_charged, mah(u.charged), duration(u.chargeTime)));
		} else {
			charged.setVisibility(View.GONE);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Formatting

	/** Milliamperes of an average: {@code microAh} drawn over {@code ms}. */
	private static double avgMa(long microAh, long ms) {
		return (ms <= 0) ? 0 : microAh * 3600.0 / ms;
	}

	/** "-342" / "+1250"; "0" for nothing. */
	private static String signed(float ma) {
		long v = Math.round(ma);
		return (v == 0) ? "0" : String.format(Locale.getDefault(), "%+d", v);
	}

	/** Charge in µAh as "12.3 mAh" (or "1.25 Ah" past an ampere hour). */
	private String mah(long microAh) {
		double m = microAh / 1000.0;
		String s;
		if (m >= 1000) s = String.format(Locale.getDefault(), "%.2f Ah", m / 1000.0);
		else if (m >= 100) s = getString(R.string.power_mah, String.format(Locale.getDefault(), "%.0f", m));
		else s = getString(R.string.power_mah, String.format(Locale.getDefault(), "%.1f", m));
		return s;
	}

	/** "2 h 13 min" / "45 min". */
	private String duration(long ms) {
		long min = ms / 60_000;
		long h = min / 60;
		min %= 60;
		return (h > 0) ? getString(R.string.power_hours_minutes, h, min) :
				getString(R.string.power_minutes, min);
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
}
