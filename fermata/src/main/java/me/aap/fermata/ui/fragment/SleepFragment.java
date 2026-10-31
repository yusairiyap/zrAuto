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
import me.aap.fermata.ui.view.LengthStepper;
import me.aap.fermata.ui.view.SettingsStyle;
import me.aap.fermata.ui.view.SleepBarsView;
import me.aap.fermata.ui.view.SleepHypnogramView;
import me.aap.fermata.ui.view.SleepGuide;
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
	private LinearLayout cols, colA, colB;
	private View settingsRow;
	// Ambience card
	private View ambienceCard;
	private TextView ambienceSub;
	private androidx.appcompat.widget.SwitchCompat ambienceSwitch;
	private androidx.appcompat.widget.SwitchCompat ambienceLinkSwitch;
	private boolean syncingAmbience;
	private final Runnable ambienceChanged = this::syncAmbience;
	// Session card
	private TextView startButton;
	private TextView sessionStatus;
	private TextView sessionSub;
	private TextView awakeChip;
	private LinearLayout buttonRow;
	// Night card
	private TextView nightTitle;
	private TextView nightEstimate;
	private SleepScoreView scoreView;
	private TextView statAsleep, statBed, statLatency, statAwake;
	private TextView batteryLine;
	private LengthStepper lengthStepper;
	private androidx.appcompat.widget.SwitchCompat stopPlaybackSwitch;
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

		// Two columns side by side where there is room (updateSplit), else one under the other.
		cols = new LinearLayout(palette);
		cols.setOrientation(LinearLayout.VERTICAL);
		cols.setClipChildren(false);
		colA = new LinearLayout(palette);
		colA.setOrientation(LinearLayout.VERTICAL);
		colA.setClipChildren(false);
		colB = new LinearLayout(palette);
		colB.setOrientation(LinearLayout.VERTICAL);
		colB.setClipChildren(false);
		cols.addView(colA, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		cols.addView(colB, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		content.addView(cols, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		parts.clear();
		addPart(colA, buildSessionCard());
		addPart(colA, buildAmbienceCard());
		addPart(colA, buildNightCard());
		addPart(colB, buildStagesCard());
		addPart(colB, buildTrendsCard());
		addPart(colB, buildInsightsCard());
		settingsRow = buildSettingsRow();
		addPart(content, settingsRow);
		return scroll;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		// tool_bar/nav_bar are drawn over the fragment: the content has to keep clear of them itself.
		a.insetScrollableContent(scroll);
		// Not during layout: switching the columns' layout requests another one.
		scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (((r - l) != (or - ol)) || ((b - t) != (ob - ot))) v.post(() -> {
				updateSplit(v);
				updateSettingsChip();
			});
		});
		scroll.post(() -> {
			updateSplit(scroll);
			updateSettingsChip();
		});
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
			// The first time the tab is opened: a short tour, which Settings > Sleep > Help brings back.
			if (!SleepPrefs.store().getBooleanPref(SleepPrefs.GUIDE_DONE)) SleepGuide.show(a);
		});
	}

	@Override
	public void onResume() {
		super.onResume();
		resumed = true;
		tracker.addListener(changed);
		if (AmbienceAddon.isEnabled()) AmbienceEngine.get().addListener(ambienceChanged);
		syncAmbience();
		syncSession();
		// The room under the tool bar is worked out from where it is: coming from a tab that had hidden it (Home) it can
		// still be on its way back, so ask again once it has settled.
		refreshInsets();
		handler.removeCallbacks(clockTick);
		if (tracker.isActive()) handler.postDelayed(clockTick, 1000);
	}

	private void refreshInsets() {
		if (scroll == null) return;
		MainActivityDelegate a = getActivityDelegate();
		scroll.post(a::refreshContentInsets);
		scroll.postDelayed(a::refreshContentInsets, 250);
		scroll.postDelayed(a::refreshContentInsets, 700);
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden) refreshInsets();
	}

	@Override
	public void onPause() {
		resumed = false;
		tracker.removeListener(changed);
		AmbienceEngine.get().removeListener(ambienceChanged);
		handler.removeCallbacksAndMessages(null);
		super.onPause();
	}

	@Override
	public void onDestroyView() {
		tracker.removeListener(changed);
		AmbienceEngine.get().removeListener(ambienceChanged);
		handler.removeCallbacksAndMessages(null);
		ambienceCard = null;
		settingsRow = null;
		cols = null;
		parts.clear();
		super.onDestroyView();
	}

	private void addPart(LinearLayout parent, View v) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		// The first card of the first column starts at the top; the column beside it too (updateSplit).
		lp.topMargin = ((parent == colA) && (parent.getChildCount() == 0)) ? 0 : px(14);
		parent.addView(v, lp);
		parts.add(v);
	}

	/**
	 * Side by side (the night and the session on the left, the charts and the tips on the right) wherever there is room for two
	 * columns: on the car screen, in landscape, and on large screens in either orientation, as the Power and Data usage tabs do.
	 */
	private void updateSplit(View root) {
		int w = root.getWidth();
		if ((w == 0) || (getContext() == null) || (cols == null)) return;
		float dpw = w / getResources().getDisplayMetrics().density;
		boolean split = getActivityDelegate().isCarActivity() || (dpw >= 720) ||
				((w > root.getHeight()) && (dpw >= 560));
		int orientation = split ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
		if (cols.getOrientation() == orientation) return;
		cols.setOrientation(orientation);
		LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) colA.getLayoutParams();
		LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) colB.getLayoutParams();
		alp.width = blp.width = split ? 0 : MATCH_PARENT;
		alp.weight = blp.weight = split ? 1f : 0f;
		blp.setMarginStart(split ? px(16) : 0);
		colA.setLayoutParams(alp);
		colB.setLayoutParams(blp);
		// Side by side, the two columns start level with each other.
		if (colB.getChildCount() > 0) {
			ViewGroup.MarginLayoutParams first = (ViewGroup.MarginLayoutParams) colB.getChildAt(0).getLayoutParams();
			first.topMargin = split ? 0 : px(14);
			colB.getChildAt(0).setLayoutParams(first);
		}
	}

	/** The settings chip: in the title bar in landscape and on the car screen, else in the tab. */
	private void updateSettingsChip() {
		ToolBarChip.update(this, R.id.sleep_toolbar_chip, settingsRow);
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
		ImageView help = new ImageView(palette);
		help.setImageResource(R.drawable.about);
		help.setImageTintList(ColorStateList.valueOf(c.textSecondary));
		help.setContentDescription(getString(R.string.sleep_help));
		help.setPadding(px(8), px(8), px(8), px(8));
		help.setBackground(c.pressable(c.rounded(0, 20)));
		help.setOnClickListener(v -> SleepGuide.show(getActivityDelegate()));
		top.addView(help, new LinearLayout.LayoutParams(px(40), px(40)));
		card.addView(top);

		startButton = text("", 16, c.onAccent, true);
		startButton.setGravity(Gravity.CENTER);
		startButton.setPadding(px(16), 0, px(16), 0);
		startButton.setClickable(true);
		startButton.setFocusable(true);
		startButton.setOnClickListener(v -> toggleSession());

		awakeChip = ChipFlowLayout.chip(palette, R.drawable.sleep, R.string.sleep_i_am_awake, false, () -> {
			tracker.markAwake();
			TopToast.show(R.drawable.sleep, R.string.sleep_marked_awake);
		});
		awakeChip.setGravity(Gravity.CENTER);

		// Stop and "I'm awake" side by side, the same height; one under the other, as wide, when they do not fit.
		buttonRow = new LinearLayout(palette);
		buttonRow.setOrientation(LinearLayout.HORIZONTAL);
		buttonRow.addView(startButton, new LinearLayout.LayoutParams(0, px(48), 1f));
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(WRAP_CONTENT, px(48));
		alp.setMarginStart(px(10));
		buttonRow.addView(awakeChip, alp);
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		rlp.topMargin = px(14);
		card.addView(buttonRow, rlp);
		buttonRow.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) v.post(this::fitButtons);
		});
		return card;
	}

	// ---- Ambience, one tap away ----

	private View buildAmbienceCard() {
		LinearLayout card = card();
		LinearLayout row = new LinearLayout(palette);
		row.setGravity(Gravity.CENTER_VERTICAL);
		ImageView icon = new ImageView(palette);
		icon.setImageResource(R.drawable.ambience);
		icon.setImageTintList(ColorStateList.valueOf(c.accent));
		row.addView(icon, new LinearLayout.LayoutParams(px(26), px(26)));
		LinearLayout names = new LinearLayout(palette);
		names.setOrientation(LinearLayout.VERTICAL);
		names.setPadding(px(12), 0, px(8), 0);
		names.addView(text(getString(R.string.sleep_amb_title), 16, c.textPrimary, true));
		ambienceSub = text("", 12.5f, c.textSecondary, false);
		names.addView(ambienceSub);
		row.addView(names, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ambienceSwitch = new androidx.appcompat.widget.SwitchCompat(palette);
		me.aap.fermata.ui.view.SwitchStyle.apply(ambienceSwitch, palette);
		ambienceSwitch.setOnCheckedChangeListener((b, on) -> {
			if (syncingAmbience || !AmbienceAddon.isEnabled()) return;
			AmbienceEngine e = AmbienceEngine.get();
			if (on && !e.anyOn()) e.resumeLast();
			else if (!on && e.anyOn()) e.stopAll();
		});
		row.addView(ambienceSwitch);
		card.addView(row);

		LinearLayout link = new LinearLayout(palette);
		link.setGravity(Gravity.CENTER_VERTICAL);
		TextView lt = text(getString(R.string.sleep_amb_with), 13.5f, c.textPrimary, false);
		link.addView(lt, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ambienceLinkSwitch = new androidx.appcompat.widget.SwitchCompat(palette);
		me.aap.fermata.ui.view.SwitchStyle.apply(ambienceLinkSwitch, palette);
		ambienceLinkSwitch.setOnCheckedChangeListener((b, on) -> {
			if (syncingAmbience) return;
			SleepPrefs.store().applyBooleanPref(SleepPrefs.AMBIENCE_START, on);
			syncSession();
		});
		link.addView(ambienceLinkSwitch);
		LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		llp.topMargin = px(10);
		card.addView(link, llp);

		// How long the sounds play: chips like the timer's; the same length is the app's sleep timer.
		TextView lenLabel = text(getString(R.string.sleep_amb_fade), 13.5f, c.textPrimary, false);
		LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lblp.topMargin = px(12);
		card.addView(lenLabel, lblp);
		// - [ 30 min ] + : any length in steps of five minutes. The Ambience tab has the same control for the same length.
		lengthStepper = new LengthStepper(palette, c.textPrimary,
				() -> SleepPrefs.store().getIntPref(SleepPrefs.AMBIENCE_MIN), this::setAmbienceLength);
		LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lcp.topMargin = px(6);
		card.addView(lengthStepper, lcp);

		LinearLayout stop = new LinearLayout(palette);
		stop.setGravity(Gravity.CENTER_VERTICAL);
		TextView st = text(getString(R.string.sleep_set_stop_playback), 13.5f, c.textPrimary, false);
		stop.addView(st, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		stopPlaybackSwitch = new androidx.appcompat.widget.SwitchCompat(palette);
		me.aap.fermata.ui.view.SwitchStyle.apply(stopPlaybackSwitch, palette);
		stopPlaybackSwitch.setChecked(SleepPrefs.store().getBooleanPref(SleepPrefs.STOP_PLAYBACK));
		stopPlaybackSwitch.setOnCheckedChangeListener((b, on) -> {
			if (syncingAmbience) return;
			SleepPrefs.store().applyBooleanPref(SleepPrefs.STOP_PLAYBACK, on);
		});
		stop.addView(stopPlaybackSwitch);
		LinearLayout.LayoutParams stp2 = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		stp2.topMargin = px(10);
		card.addView(stop, stp2);

		TextView sounds = ChipFlowLayout.chip(palette, R.drawable.ambience, R.string.sleep_amb_sounds, false,
				() -> getActivityDelegate().showFragment(R.id.ambience_addon));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP_CONTENT, px(40));
		slp.topMargin = px(10);
		card.addView(sounds, slp);
		ambienceCard = card;
		return card;
	}

	/** The engine stores it and reschedules a running timer; without the addon the preference alone is kept. */
	private void setAmbienceLength(int minutes) {
		if (AmbienceAddon.isEnabled()) AmbienceEngine.get().setLengthMinutes(minutes);
		else SleepPrefs.store().applyIntPref(SleepPrefs.AMBIENCE_MIN, minutes);
	}

	private void showLength() {
		if (lengthStepper != null) lengthStepper.refresh();
	}

	/** The ambience card follows what plays; it is not there without the Ambience addon. */
	private void syncAmbience() {
		if (ambienceCard == null) return;
		boolean on = AmbienceAddon.isEnabled();
		ambienceCard.setVisibility(on ? View.VISIBLE : View.GONE);
		if (!on) return;
		AmbienceEngine e = AmbienceEngine.get();
		syncingAmbience = true;
		ambienceSwitch.setChecked(e.anyOn());
		ambienceLinkSwitch.setChecked(SleepPrefs.store().getBooleanPref(SleepPrefs.AMBIENCE_START));
		stopPlaybackSwitch.setChecked(SleepPrefs.store().getBooleanPref(SleepPrefs.STOP_PLAYBACK));
		showLength();
		syncingAmbience = false;
		String d = e.describe(requireContext());
		ambienceSub.setText(e.anyOn() && !d.isEmpty() ? d : getString(R.string.sleep_amb_off));
		syncSession();
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
		if (buttonRow != null) buttonRow.post(this::fitButtons);
		if (on) {
			long elapsed = System.currentTimeMillis() - tracker.getStart();
			sessionStatus.setText(getString(R.string.sleep_recording, SleepInsights.duration(elapsed)));
			StringBuilder sb = new StringBuilder();
			sb.append(getString(R.string.sleep_since, SleepInsights.clock(getContext(), tracker.getStart())));
			if (tracker.isStandMode()) {
				sb.append(" · ").append(getString(R.string.sleep_live_stand, Math.round(tracker.getLiveMovement() / 2.55f)));
			} else if (tracker.isRecordingMovement()) {
				sb.append(" · ").append(getString(R.string.sleep_live_move, Math.round(tracker.getLiveMovement() / 2.55f)));
			} else {
				sb.append(" · ").append(getString(R.string.sleep_times_only));
			}
			if (tracker.isRecordingSound()) sb.append(" · ").append(getString(R.string.sleep_live_sound));
			sessionSub.setText(sb);
		} else {
			long auto = me.aap.fermata.addon.sleep.SleepReminder.nextAutoStart();
			if (auto < 0) {
				sessionStatus.setText(R.string.sleep_ready);
				sessionSub.setText(R.string.sleep_ready_sub);
			} else {
				long wait = auto - System.currentTimeMillis();
				String at = DateFormat.getTimeFormat(requireContext()).format(new Date(auto));
				if (wait < 60_000L) {
					sessionStatus.setText(R.string.sleep_ready_auto_now);
					sessionSub.setText(R.string.sleep_ready_auto_now_sub);
				} else {
					sessionStatus.setText(getString(R.string.sleep_ready_auto, at));
					sessionSub.setText(getString(R.string.sleep_ready_auto_sub, SleepInsights.duration(wait)));
				}
			}
		}
	}

	/** Stop and "I'm awake" next to each other when the card is wide enough for both, else one under the other. */
	private void fitButtons() {
		if ((buttonRow == null) || (startButton == null) || (awakeChip == null)) return;
		boolean both = awakeChip.getVisibility() == View.VISIBLE;
		boolean stack = both && (buttonRow.getWidth() < px(330));
		int orientation = stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
		if (buttonRow.getOrientation() == orientation) return;
		buttonRow.setOrientation(orientation);
		LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) startButton.getLayoutParams();
		LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) awakeChip.getLayoutParams();
		slp.width = stack ? MATCH_PARENT : 0;
		slp.weight = stack ? 0f : 1f;
		alp.width = stack ? MATCH_PARENT : WRAP_CONTENT;
		alp.setMarginStart(stack ? 0 : px(10));
		alp.topMargin = stack ? px(10) : 0;
		startButton.setLayoutParams(slp);
		awakeChip.setLayoutParams(alp);
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
		nightEstimate.setText(r.night.recovered ? getString(R.string.sleep_recovered) :
				r.night.stand ? getString(R.string.sleep_from_sound) :
						r.fromSensors ? getString(R.string.sleep_from_sensors) : getString(R.string.sleep_from_times));
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
