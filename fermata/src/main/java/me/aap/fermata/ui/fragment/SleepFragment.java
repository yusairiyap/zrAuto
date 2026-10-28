package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.Manifest;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceAddon;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.addon.sleep.SleepAnalyzer;
import me.aap.fermata.addon.sleep.SleepInsights;
import me.aap.fermata.addon.sleep.SleepNight;
import me.aap.fermata.addon.sleep.SleepPrefs;
import me.aap.fermata.addon.sleep.SleepTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.SettingsStyle;
import me.aap.fermata.ui.view.SleepBarsView;
import me.aap.fermata.ui.view.SleepHypnogramView;
import me.aap.fermata.ui.view.SleepScoreView;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The Sleep tab (see {@link SleepTracker}): the last night's score as a ring with the four parts it is made of, its stages drawn
 * across the night, the last week or month as bars (how long, or when), and in words what the nights say with tips that tie in
 * the Ambience sounds. A card at the top starts and stops the recording and shows it live. Built in code in the Music palette like
 * the Ambience and Data usage tabs; the cards slide in one after another and the charts draw themselves.
 */
public class SleepFragment extends MainActivityFragment implements ToolBarChip.Host {
	private static final ToolBarChip.Mediator TOOL_BAR = new ToolBarChip.Mediator(
			R.id.sleep_toolbar_chip, R.drawable.settings, R.string.sleep_settings);
	private static int rangeNights = 7;
	private static int barsMode = SleepBarsView.MODE_DURATION;

	private final SleepTracker tracker = SleepTracker.get();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable changed = this::onTrackerChanged;
	private final Runnable clockTick = new Runnable() {
		@Override
		public void run() {
			if (!resumed || !tracker.isActive()) return;
			syncSession();
			handler.postDelayed(this, 1000);
		}
	};
	private List<SleepAnalyzer.Result> results = new ArrayList<>();
	private long selectedStart;
	private boolean resumed;
	private Context palette;
	private SettingsStyle.Colors c;
	private float dp;
	private NestedScrollView scroll;
	private LinearLayout content;
	// Session card
	private TextView startButton;
	private TextView sessionStatus;
	private TextView sessionSub;
	private TextView awakeChip;
	private TextView mixLine;
	// Night card
	private TextView nightTitle;
	private TextView nightEstimate;
	private SleepScoreView scoreView;
	private TextView statAsleep, statBed, statLatency, statAwake;
	private TextView batteryLine;
	private View prevNight, nextNight;
	// Stage card
	private SleepHypnogramView hypno;
	private final TextView[] legend = new TextView[4];
	// Trends
	private SleepBarsView bars;
	private TextView trendSummary;
	private LinearLayout rangeSeg, modeSeg;
	// Insights
	private LinearLayout insightBox;
	private final List<View> parts = new ArrayList<>();

	@Override
	public int getFragmentId() {
		return R.id.sleep_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.sleep_title);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return TOOL_BAR;
	}

	@Override
	public void onToolBarChipClick() {
		getActivityDelegate().showFragment(R.id.sleep_settings_fragment, SleepSettingsFragment.FROM_TAB);
	}

	// ---------------------------------------------------------------------------------------------
	// Building

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = EffectsUi.palette(ctx);
		c = new SettingsStyle.Colors(ctx);
		dp = c.density;

		scroll = new NestedScrollView(ctx);
		scroll.setFillViewport(true);
		scroll.setClipToPadding(false);
		content = new LinearLayout(palette);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setClipChildren(false);
		content.setClipToPadding(false);
		// Room under the last card, so the floating buttons can be pressed with the tab scrolled to the end.
		content.setPadding(px(16), px(12), px(16), px(104));
		scroll.addView(content, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		parts.clear();
		addPart(buildSessionCard());
		addPart(buildNightCard());
		addPart(buildStagesCard());
		addPart(buildTrendsCard());
		addPart(buildInsightsCard());
		addPart(buildSettingsRow());
		return scroll;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		// tool_bar/nav_bar are drawn over the fragment: the content has to keep clear of them itself.
		a.insetScrollableContent(scroll);
		refresh();
		for (View v : parts) {
			v.setAlpha(0f);
			v.setTranslationY(px(28));
		}
		scroll.post(() -> {
			int i = 0;
			for (View v : parts) {
				v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i++).setDuration(420)
						.setInterpolator(new DecelerateInterpolator(2f)).start();
			}
			scoreView.replay();
			hypno.replay();
			bars.replay();
		});
	}

	@Override
	public void onResume() {
		super.onResume();
		resumed = true;
		tracker.addListener(changed);
		syncSession();
		handler.removeCallbacks(clockTick);
		if (tracker.isActive()) handler.postDelayed(clockTick, 1000);
	}

	@Override
	public void onPause() {
		resumed = false;
		tracker.removeListener(changed);
		handler.removeCallbacksAndMessages(null);
		super.onPause();
	}

	@Override
	public void onDestroyView() {
		tracker.removeListener(changed);
		handler.removeCallbacksAndMessages(null);
		parts.clear();
		super.onDestroyView();
	}

	private void addPart(View v) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = px(14);
		content.addView(v, lp);
		parts.add(v);
	}

	private LinearLayout card() {
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackground(c.tile(24, false));
		card.setPadding(px(18), px(16), px(18), px(16));
		return card;
	}

	private TextView text(CharSequence s, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private TextView heading(int res) {
		TextView t = text(getString(res), 13, c.accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		return t;
	}

	private int px(float v) {
		return Math.round(v * dp);
	}

	// ---- The session ----

	private View buildSessionCard() {
		LinearLayout card = card();
		LinearLayout top = new LinearLayout(palette);
		top.setGravity(Gravity.CENTER_VERTICAL);
		ImageView icon = new ImageView(palette);
		icon.setImageResource(R.drawable.sleep);
		icon.setImageTintList(ColorStateList.valueOf(c.accent));
		top.addView(icon, new LinearLayout.LayoutParams(px(30), px(30)));
		LinearLayout names = new LinearLayout(palette);
		names.setOrientation(LinearLayout.VERTICAL);
		names.setPadding(px(12), 0, px(8), 0);
		sessionStatus = text("", 17, c.textPrimary, true);
		sessionSub = text("", 12.5f, c.textSecondary, false);
		names.addView(sessionStatus);
		names.addView(sessionSub);
		top.addView(names, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		card.addView(top);

		startButton = text("", 16, c.onAccent, true);
		startButton.setGravity(Gravity.CENTER);
		startButton.setPadding(px(16), px(14), px(16), px(14));
		startButton.setClickable(true);
		startButton.setFocusable(true);
		startButton.setOnClickListener(v -> toggleSession());
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = px(14);
		card.addView(startButton, blp);

		awakeChip = ChipFlowLayout.chip(palette, R.drawable.sleep, R.string.sleep_i_am_awake, false, () -> {
			tracker.markAwake();
			TopToast.show(R.drawable.sleep, R.string.sleep_marked_awake);
		});
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(WRAP_CONTENT, px(40));
		alp.topMargin = px(10);
		alp.gravity = Gravity.CENTER_HORIZONTAL;
		card.addView(awakeChip, alp);

		mixLine = text("", 12.5f, c.textSecondary, false);
		mixLine.setPadding(0, px(12), 0, 0);
		card.addView(mixLine);
		return card;
	}

	private void toggleSession() {
		if (tracker.isActive()) {
			SleepNight n = tracker.stop();
			if (n == null) {
				TopToast.show(R.drawable.sleep, R.string.sleep_too_short);
			} else {
				selectedStart = n.start;
				TopToast.show(R.drawable.sleep, R.string.sleep_saved);
			}
			refresh();
			replayCharts();
			return;
		}
		MainActivityDelegate a = getActivityDelegate();
		boolean wantsSound = SleepPrefs.store().getIntPref(SleepPrefs.MODE) >= SleepPrefs.MODE_MOVEMENT_SOUND;
		if (wantsSound && !SleepTracker.hasMicPermission() && !a.isCarActivityNotMirror()) {
			// Asked once here; the session runs on movement alone if it is refused.
			a.getAppActivity().checkPermissions(Manifest.permission.RECORD_AUDIO).onCompletion((r, err) -> handler.post(this::begin));
		} else {
			begin();
		}
	}

	private void begin() {
		tracker.start();
		TopToast.show(R.drawable.sleep, R.string.sleep_started);
		syncSession();
		handler.removeCallbacks(clockTick);
		handler.postDelayed(clockTick, 1000);
	}

	private void onTrackerChanged() {
		if (getView() == null) return;
		syncSession();
	}

	/** The session card: what is going on now. */
	private void syncSession() {
		if (startButton == null) return;
		boolean on = tracker.isActive();
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(px(18));
		bg.setColor(on ? 0xFFE57373 : c.accent);
		startButton.setBackground(c.pressable(bg));
		startButton.setTextColor(on ? 0xFF1C1B1F : c.onAccent);
		startButton.setText(on ? R.string.sleep_stop : R.string.sleep_start);
		awakeChip.setVisibility(on ? View.VISIBLE : View.GONE);
		if (on) {
			long elapsed = System.currentTimeMillis() - tracker.getStart();
			sessionStatus.setText(getString(R.string.sleep_recording, SleepInsights.duration(elapsed)));
			StringBuilder sb = new StringBuilder();
			sb.append(getString(R.string.sleep_since, SleepInsights.clock(getContext(), tracker.getStart())));
			if (tracker.isRecordingMovement()) {
				sb.append(" · ").append(getString(R.string.sleep_live_move, Math.round(tracker.getLiveMovement() / 2.55f)));
			} else {
				sb.append(" · ").append(getString(R.string.sleep_times_only));
			}
			if (tracker.isRecordingSound()) sb.append(" · ").append(getString(R.string.sleep_live_sound));
			sessionSub.setText(sb);
		} else {
			sessionStatus.setText(R.string.sleep_ready);
			sessionSub.setText(R.string.sleep_ready_sub);
		}
		String mix = "";
		if (AmbienceAddon.isEnabled()) {
			mix = AmbienceEngine.get().describe(getContext());
		}
		boolean link = SleepPrefs.store().getBooleanPref(SleepPrefs.AMBIENCE_START) && AmbienceAddon.isEnabled();
		if (link && !on) {
			int min = SleepPrefs.store().getIntPref(SleepPrefs.AMBIENCE_MIN);
			mixLine.setVisibility(View.VISIBLE);
			mixLine.setText(min > 0 ? getString(R.string.sleep_link_with_timer, min) : getString(R.string.sleep_link));
		} else if (on && !mix.isEmpty()) {
			mixLine.setVisibility(View.VISIBLE);
			mixLine.setText(getString(R.string.sleep_link_playing, mix));
		} else {
			mixLine.setVisibility(View.GONE);
		}
	}

	// ---- The night ----

	private View buildNightCard() {
		LinearLayout card = card();
		LinearLayout head = new LinearLayout(palette);
		head.setGravity(Gravity.CENTER_VERTICAL);
		prevNight = arrow(R.drawable.data_usage_chevron_left, -1);
		head.addView(prevNight, new LinearLayout.LayoutParams(px(40), px(40)));
		LinearLayout names = new LinearLayout(palette);
		names.setOrientation(LinearLayout.VERTICAL);
		names.setGravity(Gravity.CENTER_HORIZONTAL);
		nightTitle = text("", 16, c.textPrimary, true);
		nightTitle.setGravity(Gravity.CENTER);
		nightEstimate = text("", 12, c.textSecondary, false);
		nightEstimate.setGravity(Gravity.CENTER);
		names.addView(nightTitle);
		names.addView(nightEstimate);
		head.addView(names, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		nextNight = arrow(R.drawable.data_usage_chevron_right, 1);
		head.addView(nextNight, new LinearLayout.LayoutParams(px(40), px(40)));
		card.addView(head);

		scoreView = new SleepScoreView(palette, c);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		slp.topMargin = px(8);
		card.addView(scoreView, slp);

		LinearLayout stats = new LinearLayout(palette);
		stats.setBaselineAligned(false);
		TextView[] values = new TextView[4];
		int[] labels = {R.string.sleep_stat_asleep, R.string.sleep_stat_in_bed, R.string.sleep_stat_latency,
				R.string.sleep_stat_awake};
		for (int i = 0; i < 4; i++) {
			LinearLayout cell = new LinearLayout(palette);
			cell.setOrientation(LinearLayout.VERTICAL);
			cell.setGravity(Gravity.CENTER_HORIZONTAL);
			values[i] = text("-", 15, c.textPrimary, true);
			values[i].setGravity(Gravity.CENTER);
			TextView l = text(getString(labels[i]), 11, c.textSecondary, false);
			l.setGravity(Gravity.CENTER);
			cell.addView(values[i]);
			cell.addView(l);
			stats.addView(cell, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		}
		statAsleep = values[0];
		statBed = values[1];
		statLatency = values[2];
		statAwake = values[3];
		LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		stp.topMargin = px(10);
		card.addView(stats, stp);

		batteryLine = text("", 12.5f, c.textSecondary, false);
		batteryLine.setGravity(Gravity.CENTER);
		batteryLine.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.power_usage, 0, 0, 0);
		batteryLine.setCompoundDrawableTintList(ColorStateList.valueOf(c.accent));
		batteryLine.setCompoundDrawablePadding(px(6));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = px(12);
		card.addView(batteryLine, blp);
		return card;
	}

	private View arrow(int icon, int dir) {
		ImageView iv = new ImageView(palette);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(c.textSecondary));
		iv.setPadding(px(8), px(8), px(8), px(8));
		iv.setBackground(c.pressable(c.rounded(0, 20)));
		iv.setOnClickListener(v -> moveNight(dir));
		return iv;
	}

	private void moveNight(int dir) {
		int i = selectedIndex() + dir;
		if ((i < 0) || (i >= results.size())) return;
		selectedStart = results.get(i).night.start;
		bars.setSelected(i);
		showSelected(true);
	}

	private int selectedIndex() {
		for (int i = 0; i < results.size(); i++) if (results.get(i).night.start == selectedStart) return i;
		return results.size() - 1;
	}

	// ---- The stages ----

	private View buildStagesCard() {
		LinearLayout card = card();
		card.addView(heading(R.string.sleep_stages_title));
		TextView sub = text(getString(R.string.sleep_stages_sub), 12, c.textSecondary, false);
		sub.setPadding(0, px(2), 0, px(8));
		card.addView(sub);
		hypno = new SleepHypnogramView(palette, c);
		card.addView(hypno, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		ChipFlowLayout row = new ChipFlowLayout(palette);
		int[] colors = {0xFFFF8A65, 0xFFBA68C8, 0xFF64B5F6, 0xFF3F51B5};
		int[] stages = {SleepAnalyzer.AWAKE, SleepAnalyzer.REM, SleepAnalyzer.LIGHT, SleepAnalyzer.DEEP};
		String[] names = getResources().getStringArray(R.array.sleep_stages);
		for (int i = 0; i < 4; i++) {
			TextView t = text(names[stages[i]], 12, c.textPrimary, false);
			t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(colors[i]), null, null, null);
			t.setCompoundDrawablePadding(px(6));
			t.setPadding(px(2), px(4), px(10), px(4));
			legend[stages[i]] = t;
			row.addView(t);
		}
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = px(8);
		card.addView(row, lp);
		return card;
	}

	private GradientDrawable dot(int color) {
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(color);
		d.setSize(px(10), px(10));
		return d;
	}

	// ---- The trends ----

	private View buildTrendsCard() {
		LinearLayout card = card();
		card.addView(heading(R.string.sleep_trends_title));
		rangeSeg = segment(new String[]{getString(R.string.sleep_range_7), getString(R.string.sleep_range_30)},
				() -> (rangeNights == 7) ? 0 : 1, i -> {
					rangeNights = (i == 0) ? 7 : 30;
					bars.setRange(rangeNights);
					syncTrends();
				});
		modeSeg = segment(new String[]{getString(R.string.sleep_mode_duration), getString(R.string.sleep_mode_times)},
				() -> barsMode, i -> {
					barsMode = i;
					bars.setMode(i);
					syncTrends();
				});
		LinearLayout segs = new LinearLayout(palette);
		segs.addView(rangeSeg, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		View gap = new View(palette);
		segs.addView(gap, new LinearLayout.LayoutParams(px(10), 1));
		segs.addView(modeSeg, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f));
		LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		sp.topMargin = px(10);
		card.addView(segs, sp);

		bars = new SleepBarsView(palette, c);
		bars.setRange(rangeNights);
		bars.setMode(barsMode);
		bars.setOnSelect(i -> {
			if ((i >= 0) && (i < results.size())) {
				selectedStart = results.get(i).night.start;
				showSelected(true);
			}
		});
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = px(8);
		card.addView(bars, blp);
		trendSummary = text("", 12.5f, c.textSecondary, false);
		trendSummary.setPadding(0, px(8), 0, 0);
		card.addView(trendSummary);
		return card;
	}

	/** A row of equal pills, the picked one filled with the accent. */
	private LinearLayout segment(String[] labels, IntSupplier get, IntConsumer set) {
		LinearLayout row = new LinearLayout(palette);
		TextView[] views = new TextView[labels.length];
		Runnable paint = () -> {
			for (int i = 0; i < views.length; i++) {
				boolean on = i == get.getAsInt();
				views[i].setTextColor(on ? c.onAccent : c.textPrimary);
				((GradientDrawable) views[i].getTag(R.id.settings_row_tag)).setColor(on ? c.accent : 0x1F808080);
			}
		};
		for (int i = 0; i < labels.length; i++) {
			int fi = i;
			TextView t = text(labels[i], 13, c.textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setPadding(px(6), px(9), px(6), px(9));
			GradientDrawable fill = c.rounded(0x1F808080, 14);
			t.setTag(R.id.settings_row_tag, fill);
			t.setBackground(c.pressable(fill));
			t.setClickable(true);
			t.setOnClickListener(v -> {
				set.accept(fi);
				paint.run();
			});
			views[i] = t;
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : px(3), 0, (i == labels.length - 1) ? 0 : px(3), 0);
			row.addView(t, lp);
		}
		row.setTag(R.id.settings_row_tag, paint);
		paint.run();
		return row;
	}

	private void syncTrends() {
		if (results.isEmpty()) {
			trendSummary.setText(R.string.sleep_no_trends);
			return;
		}
		int from = Math.max(0, results.size() - rangeNights);
		long asleep = 0;
		int score = 0;
		int n = 0;
		for (int i = from; i < results.size(); i++) {
			asleep += results.get(i).asleepMs;
			score += results.get(i).score;
			n++;
		}
		trendSummary.setText(getString(R.string.sleep_trend_summary, n, SleepInsights.duration(asleep / n), score / n));
	}

	// ---- The insights ----

	private View buildInsightsCard() {
		LinearLayout card = card();
		card.addView(heading(R.string.sleep_insights_title));
		insightBox = new LinearLayout(palette);
		insightBox.setOrientation(LinearLayout.VERTICAL);
		card.addView(insightBox, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return card;
	}

	private View buildSettingsRow() {
		LinearLayout row = new LinearLayout(palette);
		row.setGravity(Gravity.CENTER);
		TextView chip = new TextView(palette, null, 0, R.style.MusicChip);
		chip.setText(R.string.sleep_settings);
		chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.settings, 0, 0, 0);
		chip.setCompoundDrawableTintList(ColorStateList.valueOf(c.textPrimary));
		chip.setOnClickListener(v -> onToolBarChipClick());
		row.addView(chip, new LinearLayout.LayoutParams(WRAP_CONTENT, px(40)));
		return row;
	}

	// ---------------------------------------------------------------------------------------------
	// The data

	/** Reads the nights again and fills every card. */
	private void refresh() {
		List<SleepNight> all = tracker.getStore().all();
		List<SleepNight> recent = all.subList(Math.max(0, all.size() - 60), all.size());
		SleepAnalyzer.Settings st = SleepAnalyzer.Settings.fromPrefs();
		results = SleepInsights.analyzeAll(recent, st);
		if ((selectedStart == 0) || (selectedIndex() < 0) || !hasStart(selectedStart)) {
			selectedStart = results.isEmpty() ? 0 : results.get(results.size() - 1).night.start;
		}
		bars.setData(results, st.goalMin, st.bedMin, st.wakeMin);
		bars.setSelected(selectedIndex());
		insightBox.removeAllViews();
		List<String> lines = SleepInsights.build(requireContext(), results, st);
		if (lines.isEmpty()) lines.add(getString(R.string.sleep_insights_empty));
		for (String s : lines) {
			LinearLayout item = new LinearLayout(palette);
			item.setPadding(0, px(10), 0, 0);
			TextView bullet = text("•", 15, c.accent, true);
			bullet.setPadding(0, 0, px(10), 0);
			item.addView(bullet);
			TextView t = text(s, 14, c.textPrimary, false);
			t.setLineSpacing(px(2), 1f);
			item.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
			insightBox.addView(item);
		}
		syncTrends();
		syncSession();
		showSelected(false);
	}

	private boolean hasStart(long start) {
		for (SleepAnalyzer.Result r : results) if (r.night.start == start) return true;
		return false;
	}

	private void replayCharts() {
		scoreView.replay();
		hypno.replay();
		bars.replay();
	}

	/** What the night cost the battery: the average current and the percent per hour, or that it was charging. */
	private void showBattery(SleepNight n) {
		float pph = n.percentPerHour();
		if (n.hasBattery() && (pph >= 0f)) {
			batteryLine.setVisibility(View.VISIBLE);
			batteryLine.setText(getString(R.string.sleep_battery_used, Math.round(n.drawMa()), n.levelStart - n.levelEnd,
					String.format(Locale.getDefault(), "%.1f", pph)));
		} else if (n.hasBattery()) {
			batteryLine.setVisibility(View.VISIBLE);
			batteryLine.setText(getString(R.string.sleep_battery_mixed, Math.round(n.drawMa())));
		} else if (n.chargeSamples > 0) {
			batteryLine.setVisibility(View.VISIBLE);
			batteryLine.setText(R.string.sleep_battery_charging);
		} else {
			batteryLine.setVisibility(View.GONE);
		}
	}

	/** The night picked: its score, figures and stages. */
	private void showSelected(boolean animate) {
		int i = results.isEmpty() ? -1 : selectedIndex();
		SleepAnalyzer.Result r = (i < 0) ? null : results.get(i);
		prevNight.setAlpha((i > 0) ? 1f : 0.3f);
		nextNight.setAlpha((i >= 0 && i < results.size() - 1) ? 1f : 0.3f);
		if (r == null) {
			nightTitle.setText(R.string.sleep_no_night_title);
			nightEstimate.setText(R.string.sleep_no_night_sub);
			scoreView.setResult(null, null);
			statAsleep.setText("-");
			statBed.setText("-");
			statLatency.setText("-");
			statAwake.setText("-");
			batteryLine.setVisibility(View.GONE);
			hypno.setResult(null);
			for (TextView t : legend) if (t != null) t.setAlpha(0.5f);
			return;
		}
		Context ctx = requireContext();
		nightTitle.setText(DateFormat.format("EEE d MMM", new Date(r.wakeTime())));
		nightEstimate.setText(r.fromSensors ? (r.night.recovered ? getString(R.string.sleep_recovered) :
				getString(R.string.sleep_from_sensors)) : getString(R.string.sleep_from_times));
		scoreView.setResult(r, null);
		statAsleep.setText(SleepInsights.duration(r.asleepMs));
		statBed.setText(SleepInsights.clock(ctx, r.bedTime()) + "–" + SleepInsights.clock(ctx, r.wakeTime()));
		statLatency.setText(getString(R.string.sleep_minutes, r.latencyMs / 60_000L));
		statAwake.setText(String.valueOf(r.awakenings));
		showBattery(r.night);
		hypno.setResult(r);
		long[] ms = new long[4];
		ms[SleepAnalyzer.REM] = r.remMs;
		ms[SleepAnalyzer.LIGHT] = r.lightMs;
		ms[SleepAnalyzer.DEEP] = r.deepMs;
		ms[SleepAnalyzer.AWAKE] = r.wakeAfterOnsetMs + r.latencyMs;
		String[] names = getResources().getStringArray(R.array.sleep_stages);
		for (int s = 0; s < 4; s++) {
			if (legend[s] == null) continue;
			legend[s].setAlpha(1f);
			legend[s].setText(String.format(Locale.getDefault(), "%s %s", names[s], SleepInsights.duration(ms[s])));
		}
	}
}
