package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.animation.ValueAnimator;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.MenuStyle;
import me.aap.fermata.ui.view.SwitchStyle;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.DiagnosticLog.Entry;
import me.aap.fermata.util.DiagnosticLog.Level;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * Reads back what {@link DiagnosticLog} recorded, so a trace captured in the car can be reviewed, filtered, copied or
 * shared without adb. Reachable from Settings &gt; Other &gt; Diagnostics, and from the main menu when that is switched
 * on there; not a nav-bar tab, since it only exists while someone is actively chasing a bug.
 * <p>
 * One list: the first item is the header (the build's version, the recording switch, the buttons, the search box, the
 * level and the category filters), the rest are the lines, each a card with a bar in its level's colour, the time, a
 * pill in its category's colour, the message and its {@code key=value} details. What Copy and Share produce is what the
 * filters leave, oldest first, under a header that names the build.
 *
 * @see DiagnosticLog
 */
public class DiagnosticLogFragment extends MainActivityFragment {
	/**
	 * A share intent's payload crosses a Binder transaction, which is hard-capped around 1MB for the whole transaction;
	 * exceeding it throws rather than truncating. The log's own entry cap keeps it well under this in practice; this is
	 * the backstop for the pathological case, and keeps the newest (most relevant) end.
	 */
	private static final int MAX_SHARE_CHARS = 200_000;
	private static final long LIVE_MS = 1000;
	private static final long SEARCH_DELAY_MS = 250;
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ROW = 1;
	private static final int TYPE_EMPTY = 2;
	private static final int TYPE_CLASSIC = 3;

	// The filters outlive the screen (it is rebuilt each time it is opened), not the process.
	private static Level minLevel = Level.DEBUG;
	private static final Set<String> hidden = new HashSet<>();
	private static String query = "";
	private static boolean newestFirst = true;
	/** The time range the lines are limited to, in ms since the epoch; 0 = open ended. */
	private static long fromMs;
	private static long toMs;
	private static boolean live;
	/** The old look: every line as one block of plain text, to select and copy by hand. */
	private static boolean classic;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final List<Entry> filtered = new ArrayList<>();
	private final List<Entry> shown = new ArrayList<>();
	private final Adapter adapter = new Adapter();
	private List<Entry> all = Collections.emptyList();
	private TreeMap<String, Integer> counts = new TreeMap<>();
	@Nullable
	private RecyclerView list;
	@Nullable
	private ViewTreeObserver.OnPreDrawListener preDraw;
	@Nullable
	private RecyclerView preDrawView;
	private boolean topShown;
	/** Lines picked to copy or share; the pick is by line, not by position, so it survives a refresh. */
	private final Set<Entry> picked = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
	private boolean selectMode;
	@Nullable
	private LinearLayout selectBar;
	@Nullable
	private TextView selectCount;
	private boolean resumed;
	private Context palette;
	private float density;
	private int textPrimary;
	private int textSecondary;
	private int tileFill;
	private int ripple;
	private int accent;
	private int onAccent;
	private boolean lightTheme;

	// The header's parts that change.
	private LinearLayout header;
	@Nullable
	private LinearLayout sticky;
	@Nullable
	private TextView stickyCount;
	private View spacer;
	private TextView recordingSub;
	private SwitchCompat recordingSwitch;
	private boolean syncingSwitch;
	private LinearLayout levelRow;
	private LinearLayout categoryChips;
	private LinearLayout viewChips;
	private LinearLayout dateChips;
	/** The row under the search box with one toggle per group of filters (levels, time, categories, view). */
	private LinearLayout quick;
	private final Group[] groups = new Group[4];
	private int fitState = -1;
	private int rowAnims;
	private TextView resetChip;
	private Runnable searchRun;
	@Nullable
	private EditText searchEdit;
	private int lastCount = -1;
	@Nullable
	private Entry lastEntry;

	@Override
	public boolean isSettingsPage() {
		return true;
	}

	@Override
	public int getFragmentId() {
		return R.id.diagnostic_log_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getResources().getString(R.string.diagnostic_log);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.diagnostic_log, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
		super.onViewCreated(view, state);
		Context ctx = view.getContext();
		palette = EffectsUi.palette(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		textPrimary = color(R.attr.musicTextPrimary);
		textSecondary = color(R.attr.musicTextSecondary);
		tileFill = color(R.attr.musicChipFill);
		ripple = color(R.attr.musicChipRipple);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);
		lightTheme = ColorUtils.calculateLuminance(textPrimary | 0xFF000000) < 0.5;

		RecyclerView rv = view.findViewById(R.id.diagnostic_log_list);
		list = rv;
		rv.setLayoutManager(new LinearLayoutManager(ctx));
		rv.setPadding(dp(16), dp(8), dp(16), dp(16));
		rv.setItemAnimator(null);
		rv.setAdapter(adapter);
		header = buildHeader();

		FloatingActionButton top = view.findViewById(R.id.diagnostic_log_top);
		sticky = buildSticky();
		FrameLayout.LayoutParams stlp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP);
		stlp.setMargins(dp(12), 0, dp(12), 0);
		((FrameLayout) view).addView(sticky, stlp);
		// The header's room for it follows its height (the buttons wrap on a narrow screen).
		sticky.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			// Which groups of filters fit beside each other depends on the width (after the layout, never in it).
			if ((r - l) != (or - ol)) sticky.post(this::fitQuick);
			if ((b - t) == (ob - ot) || (spacer == null) || (rowAnims > 0)) return;
			ViewGroup.LayoutParams sl = spacer.getLayoutParams();
			sl.height = (b - t) + dp(10);
			spacer.setLayoutParams(sl);
		});
		selectBar = buildSelectBar();
		FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.START);
		slp.setMargins(dp(16), 0, dp(16), dp(8));
		((FrameLayout) view).addView(selectBar, slp);
		// The same soft shadow and no ripple wash as the app's own floating buttons.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			top.setOutlineAmbientShadowColor(Color.BLACK);
			top.setOutlineSpotShadowColor(Color.BLACK);
		}
		top.setRippleColor(ColorStateList.valueOf(Color.TRANSPARENT));
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(a -> {
			a.insetScrollableContent(rv);
			// Just above the back button, wherever it is now: the bottom bars' room, the button's own margin and size, a
			// gap, and whatever it's moved by (it follows the bars as they slide away). By translation, so nothing is
			// relaid out; only touched when the value changes.
			float lift = UiUtils.toPx(a.getContext(), 15 + 56 + 12);
			preDraw = () -> {
				float dy = -(rv.getPaddingBottom() + lift) + a.getFloatingButton().getTranslationY();
				if (top.getTranslationY() != dy) top.setTranslationY(dy);
				// The buttons and search stay under the tool bar, and rise to the status bar with it when it slides away.
				if (sticky != null) {
					float rest = rv.getPaddingTop() + dp(4);
					float ty = rest;
					me.aap.utils.ui.view.ToolBarView tb = a.getToolBar();
					me.aap.fermata.ui.activity.ScrollBarsController sbc = a.getScrollBars();
					float hiddenBar = ((tb == null) || (tb.getVisibility() != View.VISIBLE)) ? 1f :
							(sbc != null) ? Math.max(0f, Math.min(1f, sbc.getToolBarHidden())) : 0f;
					if (hiddenBar > 0f) {
						float topLimit = Math.min(rest, statusBarBottom(rv) + dp(4));
						ty = rest + (topLimit - rest) * hiddenBar;
					}
					if (sticky.getTranslationY() != ty) sticky.setTranslationY(ty);
				}
				// The picking bar sits just above the bottom bars, clear of the back button's column.
				float sy = -rv.getPaddingBottom();
				if ((selectBar != null) && (selectBar.getTranslationY() != sy)) selectBar.setTranslationY(sy);
				return true;
			};
			rv.getViewTreeObserver().addOnPreDrawListener(preDraw);
			preDrawView = rv;
		});
		top.setOnClickListener(v -> rv.smoothScrollToPosition(0));
		// Shown only once scrolled away from the top.
		rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
				boolean away = r.computeVerticalScrollOffset() > UiUtils.toPx(r.getContext(), 24);
				if (away == topShown) return;
				topShown = away;
				if (away) top.show();
				else top.hide();
			}
		});
		refresh(true);
	}

	@Override
	public void onResume() {
		super.onResume();
		resumed = true;
		refresh(false);
		startLive();
	}

	@Override
	public void onPause() {
		resumed = false;
		handler.removeCallbacksAndMessages(null);
		super.onPause();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) {
			handler.removeCallbacksAndMessages(null);
		} else if (list != null) {
			refresh(false);
			startLive();
		}
	}

	@Override
	public void onDestroyView() {
		handler.removeCallbacksAndMessages(null);
		if ((preDraw != null) && (preDrawView != null)) {
			ViewTreeObserver o = preDrawView.getViewTreeObserver();
			if (o.isAlive()) o.removeOnPreDrawListener(preDraw);
		}
		preDraw = null;
		preDrawView = null;
		topShown = false;
		list = null;
		selectBar = null;
		selectCount = null;
		sticky = null;
		stickyCount = null;
		spacer = null;
		header = null;
		searchEdit = null;
		lastCount = -1;
		lastEntry = null;
		super.onDestroyView();
	}

	/** How far down the status bar (and a cut-out) reaches, in {@code v}'s own coordinates. */
	private static float statusBarBottom(View v) {
		androidx.core.view.WindowInsetsCompat i = androidx.core.view.ViewCompat.getRootWindowInsets(v);
		if (i == null) return 0f;
		int top = i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() |
				androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top;
		int[] loc = new int[2];
		v.getLocationOnScreen(loc);
		return Math.max(0, top - loc[1]);
	}

	// ---------------------------------------------------------------------------------------------
	// Data

	/**
	 * Reads the log again and applies the filters.
	 *
	 * @param toTop whether to go back to the top of the list (a filter changed, or the user asked): the newest entries
	 *              are what anyone opening or refreshing this wants
	 */
	private void refresh(boolean toTop) {
		refresh(toTop, toTop ? SCROLL_TOP : SCROLL_NONE);
	}

	/** A search changed the lines: show them where they are, straight under the buttons, scrolling there smoothly. */
	private void refreshForSearch() {
		refresh(true, SCROLL_RESULTS);
	}

	private static final int SCROLL_NONE = 0;
	private static final int SCROLL_TOP = 1;
	private static final int SCROLL_RESULTS = 2;

	private void refresh(boolean force, int scroll) {
		if (list == null) return;
		all = DiagnosticLog.snapshot();
		Entry last = all.isEmpty() ? null : all.get(all.size() - 1);
		boolean same = (all.size() == lastCount) && (last == lastEntry);
		lastCount = all.size();
		lastEntry = last;
		if (same && !force) {
			syncHeader();
			return;
		}
		TreeMap<String, Integer> now = DiagnosticLog.countByCategory(all);
		boolean categoriesChanged = !now.equals(counts);
		counts = now;
		applyFilter();
		if (categoriesChanged) buildCategoryChips();
		syncHeader();
		adapter.notifyDataSetChanged();
		if (list == null) return;
		if (scroll == SCROLL_TOP) list.scrollToPosition(0);
		else if (scroll == SCROLL_RESULTS) scrollToResults();
	}

	/** Brings the first line to just under the floating buttons and search box. */
	private void scrollToResults() {
		RecyclerView rv = list;
		if ((rv == null) || !(rv.getLayoutManager() instanceof LinearLayoutManager lm)) return;
		int offset = ((sticky == null) ? 0 : sticky.getHeight()) + dp(10);
		androidx.recyclerview.widget.LinearSmoothScroller s = new androidx.recyclerview.widget.LinearSmoothScroller(
				rv.getContext()) {
			@Override
			protected int getVerticalSnapPreference() {
				return SNAP_TO_START;
			}

			@Override
			public int calculateDyToMakeVisible(View view, int snapPreference) {
				return super.calculateDyToMakeVisible(view, snapPreference) + offset;
			}
		};
		s.setTargetPosition(1);
		rv.post(() -> lm.startSmoothScroll(s));
	}

	private void applyFilter() {
		filtered.clear();
		String needle = query.trim().toLowerCase(Locale.ROOT);
		for (Entry e : all) {
			if (e.level.ordinal() < minLevel.ordinal()) continue;
			if (hidden.contains(e.category)) continue;
			if ((fromMs > 0) && (e.time < fromMs)) continue;
			if ((toMs > 0) && ((e.time == 0) || (e.time > toMs))) continue;
			if (!needle.isEmpty() && !e.matches(needle)) continue;
			filtered.add(e);
		}
		shown.clear();
		shown.addAll(filtered);
		if (newestFirst) Collections.reverse(shown);
	}

	private boolean filtersActive() {
		return (minLevel != Level.DEBUG) || !hidden.isEmpty() || !query.trim().isEmpty() || (fromMs > 0) || (toMs > 0);
	}

	private String filterText() {
		StringBuilder sb = new StringBuilder("level>=").append(minLevel.name());
		if (!hidden.isEmpty()) {
			List<String> h = new ArrayList<>(hidden);
			Collections.sort(h);
			sb.append(" hidden=").append(String.join(",", h));
		}
		if (fromMs > 0) sb.append(" from=").append(stamp(fromMs));
		if (toMs > 0) sb.append(" to=").append(stamp(toMs));
		if (!query.trim().isEmpty()) sb.append(" search=\"").append(query.trim()).append('"');
		return sb.toString();
	}

	private void startLive() {
		handler.removeCallbacks(liveTick);
		if (live && resumed) handler.postDelayed(liveTick, LIVE_MS);
	}

	private final Runnable liveTick = new Runnable() {
		@Override
		public void run() {
			if (!live || !resumed || (list == null)) return;
			refresh(false);
			handler.postDelayed(this, LIVE_MS);
		}
	};

	// ---------------------------------------------------------------------------------------------
	// The header

	private LinearLayout buildHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);
		h.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The card: what build this is, the count, the recording switch.
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(16), dp(14), dp(16), dp(12));
		card.setBackground(rounded(tileFill, 16));
		LinearLayout top = new LinearLayout(palette);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		ImageView icon = new ImageView(palette);
		icon.setImageResource(R.drawable.diagnostic_log);
		icon.setImageTintList(ColorStateList.valueOf(accent));
		top.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
		LinearLayout names = new LinearLayout(palette);
		names.setOrientation(LinearLayout.VERTICAL);
		names.setPadding(dp(14), 0, 0, 0);
		names.addView(text(getString(R.string.diagnostic_log), 18, textPrimary, true));
		TextView version = text(DiagnosticLog.appVersion(), 12.5f, textSecondary, false);
		version.setTextIsSelectable(true);
		names.addView(version);
		top.addView(names, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		card.addView(top);


		LinearLayout rec = new LinearLayout(palette);
		rec.setOrientation(LinearLayout.HORIZONTAL);
		rec.setGravity(Gravity.CENTER_VERTICAL);
		rec.setPadding(0, dp(6), 0, 0);
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(getString(R.string.diag_recording), 15, textPrimary, true));
		recordingSub = text("", 12.5f, textSecondary, false);
		labels.addView(recordingSub);
		rec.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		recordingSwitch = new SwitchCompat(palette);
		SwitchStyle.apply(recordingSwitch, palette);
		rec.addView(recordingSwitch);
		recordingSwitch.setOnCheckedChangeListener((b, on) -> {
			if (syncingSwitch) return;
			getActivityDelegate().getPrefs().applyBooleanPref(MainActivityPrefs.DEBUG_LOG_ENABLED, on);
			recordingSub.setText(on ? R.string.diag_recording_on : R.string.diag_recording_off);
			// Turning it on loads the previous run's lines a moment later.
			handler.postDelayed(() -> refresh(false), 600);
		});
		rec.setOnClickListener(v -> recordingSwitch.toggle());
		card.addView(rec);
		TextView keeps = text(getString(R.string.diag_retention), 12, textSecondary, false);
		keeps.setPadding(0, dp(2), 0, dp(6));
		card.addView(keeps);
		card.addView(prefSwitch(R.string.debug_log_toasts, R.string.debug_log_toasts_sub, MainActivityPrefs.DEBUG_LOG_TOASTS));
		card.addView(prefSwitch(R.string.debug_log_menu, R.string.debug_log_menu_sub, MainActivityPrefs.DEBUG_LOG_MENU));
		h.addView(card, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// Room at the top for the buttons and the search box, which float over the list (buildSticky) and stay put.
		spacer = new View(palette);
		h.addView(spacer, 0, new LinearLayout.LayoutParams(MATCH_PARENT, dp(112)));

		View space = new View(palette);
		h.addView(space, new LinearLayout.LayoutParams(MATCH_PARENT, dp(6)));
		return h;
	}

	/** A row with a switch that is one of the diagnostics settings (they live here, not in Settings). */
	private View prefSwitch(@StringRes int title, @StringRes int sub,
													me.aap.utils.pref.PreferenceStore.Pref<me.aap.utils.function.BooleanSupplier> pref) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, dp(10), 0, 0);
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(getString(title), 15, textPrimary, true));
		labels.addView(text(getString(sub), 12.5f, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(palette);
		SwitchStyle.apply(sw, palette);
		sw.setChecked(getActivityDelegate().getPrefs().getBooleanPref(pref));
		sw.setOnCheckedChangeListener((b, on) -> getActivityDelegate().getPrefs().applyBooleanPref(pref, on));
		row.addView(sw);
		row.setOnClickListener(v -> sw.toggle());
		return row;
	}

	/**
	 * The buttons and the search box: one card over the list, so they stay in reach however far the log is scrolled, and
	 * the search box keeps the keyboard while the list under it changes.
	 */
	private LinearLayout buildSticky() {
		LinearLayout bar = new LinearLayout(palette);
		bar.setOrientation(LinearLayout.VERTICAL);
		bar.setPadding(dp(10), dp(8), dp(10), dp(8));
		GradientDrawable bg = rounded(ColorUtils.setAlphaComponent(MenuStyle.panelColor(palette), 0xF2), 22);
		bar.setBackground(bg);
		bar.setElevation(dp(6));
		ChipFlowLayout actions = new ChipFlowLayout(palette);
		actions.addView(ChipFlowLayout.chip(palette, R.drawable.copy, R.string.diagnostic_log_copy, true, this::copy));
		actions.addView(ChipFlowLayout.chip(palette, R.drawable.share, R.string.diagnostic_log_share, false, this::share));
		actions.addView(ChipFlowLayout.chip(palette, R.drawable.refresh, R.string.diagnostic_log_refresh, false,
				() -> refresh(true)));
		actions.addView(ChipFlowLayout.chip(palette, R.drawable.delete, R.string.diagnostic_log_clear, false, this::clear));
		// The buttons on the left, and at the top right how many lines the filters leave.
		LinearLayout top = new LinearLayout(palette);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.addView(actions, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		stickyCount = text("", 12.5f, accent, true);
		stickyCount.setGravity(Gravity.END | Gravity.TOP);
		stickyCount.setPadding(dp(8), dp(10), dp(6), 0);
		top.addView(stickyCount, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		bar.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		// Not on the car screen, where there is no keyboard.
		if (!getActivityDelegate().isCarActivity()) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.topMargin = dp(8);
			bar.addView(buildSearch(), lp);
		}
		bar.addView(buildFilters(), new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return bar;
	}

	// ---------------------------------------------------------------------------------------------
	// The filters in the floating panel

	private static final int G_LEVELS = 0;
	private static final int G_TIME = 1;
	private static final int G_CATS = 2;
	private static final int G_VIEW = 3;

	/** One group of filters: its toggle in the quick row (an icon, with the name when there is room) and the row it opens. */
	private final class Group {
		final LinearLayout toggle;
		final ImageView icon;
		final TextView label;
		final LinearLayout row;
		final GradientDrawable fill = rounded(0x1F808080, 20);
		boolean open;
		/** Levels only: shown right in the quick row instead of behind the toggle. */
		boolean inline;
		@Nullable
		ValueAnimator anim;

		Group(@DrawableRes int iconRes, @StringRes int name, Runnable click) {
			toggle = new LinearLayout(palette);
			toggle.setOrientation(LinearLayout.HORIZONTAL);
			toggle.setGravity(Gravity.CENTER);
			toggle.setPadding(dp(10), 0, dp(10), 0);
			toggle.setMinimumHeight(dp(40));
			toggle.setBackground(pressable(fill));
			toggle.setClickable(true);
			toggle.setFocusable(true);
			toggle.setOnClickListener(v -> click.run());
			toggle.setContentDescription(getString(name));
			icon = new ImageView(palette);
			icon.setImageResource(iconRes);
			toggle.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
			label = text(getString(name), 13.5f, textPrimary, true);
			label.setSingleLine(true);
			label.setPadding(dp(6), 0, 0, 0);
			label.setVisibility(View.GONE);
			toggle.addView(label);
			row = new LinearLayout(palette);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setPadding(0, dp(8), 0, 0);
			row.setVisibility(View.GONE);
			row.setAlpha(0f);
		}
	}

	private View buildFilters() {
		LinearLayout all = new LinearLayout(palette);
		all.setOrientation(LinearLayout.VERTICAL);
		quick = new LinearLayout(palette);
		quick.setOrientation(LinearLayout.HORIZONTAL);
		quick.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		qlp.topMargin = dp(8);

		buildLevelRow();
		categoryChips = new LinearLayout(palette);
		viewChips = new LinearLayout(palette);
		dateChips = new LinearLayout(palette);
		groups[G_LEVELS] = new Group(R.drawable.diag_levels, R.string.diag_filter_levels, () -> toggleGroup(G_LEVELS));
		groups[G_TIME] = new Group(R.drawable.diag_calendar, R.string.diag_filter_time, () -> toggleGroup(G_TIME));
		groups[G_CATS] = new Group(R.drawable.diag_tag, R.string.diag_filter_categories, () -> toggleGroup(G_CATS));
		groups[G_VIEW] = new Group(R.drawable.visibility, R.string.diag_filter_view, () -> toggleGroup(G_VIEW));
		groups[G_LEVELS].row.addView(levelRow);
		groups[G_TIME].row.addView(fadeScroller(dateChips), new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		groups[G_CATS].row.addView(fadeScroller(categoryChips), new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		groups[G_VIEW].row.addView(fadeScroller(viewChips), new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		boolean car = getActivityDelegate().isCarActivity();
		for (int g = 0; g < groups.length; g++) {
			// The pickers are real dialogs, which the car screen does not allow.
			if (car && (g == G_TIME)) continue;
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(40));
			lp.setMarginEnd(dp(6));
			quick.addView(groups[g].toggle, lp);
		}
		all.addView(quick, qlp);
		for (int g = 0; g < groups.length; g++) {
			if (car && (g == G_TIME)) continue;
			all.addView(groups[g].row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		}
		buildDateChips();
		buildViewChips();
		syncToggles();
		return all;
	}

	/** A row of chips that scrolls sideways and melts away at the edge it can still scroll to. */
	private View fadeScroller(LinearLayout content) {
		android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(palette);
		hsv.setHorizontalScrollBarEnabled(false);
		hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
		hsv.setFillViewport(false);
		content.setOrientation(LinearLayout.HORIZONTAL);
		content.setGravity(Gravity.CENTER_VERTICAL);
		hsv.addView(content, new android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		me.aap.fermata.ui.home.HomeFadeLayout fade = new me.aap.fermata.ui.home.HomeFadeLayout(palette);
		fade.addView(hsv, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		fade.follow(hsv, null);
		return fade;
	}

	private void addChip(LinearLayout parent, View chip) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.setMarginEnd(dp(6));
		parent.addView(chip, lp);
	}

	private void buildLevelRow() {
		levelRow = new LinearLayout(palette);
		levelRow.setOrientation(LinearLayout.HORIZONTAL);
		int[] names = {R.string.diag_level_all, R.string.diag_level_info, R.string.diag_level_warn, R.string.diag_level_error};
		Level[] levels = Level.values();
		for (int i = 0; i < levels.length; i++) {
			int fi = i;
			TextView t = text(getString(names[i]), 13.5f, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setPadding(dp(12), 0, dp(12), 0);
			GradientDrawable fill = rounded(0x1F808080, 20);
			t.setTag(R.id.settings_row_tag, fill);
			t.setBackground(pressable(fill));
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> {
				minLevel = levels[fi];
				syncLevel();
				syncToggles();
				refresh(true);
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(40));
			lp.setMarginEnd(dp(4));
			levelRow.addView(t, lp);
		}
		syncLevel();
	}

	/**
	 * Which groups show their name (and the levels their chips) in the quick row: the levels first, then the time range, the
	 * categories and the view, each only while the width still holds it; the rest are an icon, and a tap on one opens its row
	 * under the others.
	 */
	private void fitQuick() {
		if ((quick == null) || (groups[G_LEVELS] == null)) return;
		int avail = quick.getWidth() - quick.getPaddingLeft() - quick.getPaddingRight();
		if (avail <= 0) return;
		boolean car = getActivityDelegate().isCarActivity();
		int icon = dp(40);
		int gap = dp(6);
		int used = 0;
		int[] order = {G_LEVELS, G_TIME, G_CATS, G_VIEW};
		for (int g : order) if (!(car && (g == G_TIME))) used += icon + gap;
		int state = 0;
		for (int g : order) {
			if (car && (g == G_TIME)) continue;
			int wide;
			if (g == G_LEVELS) {
				levelRow.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
				wide = levelRow.getMeasuredWidth() + gap - dp(4);
			} else {
				Group x = groups[g];
				x.label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
				wide = icon + x.label.getMeasuredWidth() + dp(6);
			}
			int delta = wide - icon;
			if (used + delta <= avail) {
				used += delta;
				state |= 1 << g;
			}
		}
		if (state == fitState) return;
		fitState = state;
		// The levels: in the quick row, or in their own row behind the toggle.
		Group lv = groups[G_LEVELS];
		boolean inline = (state & 1) != 0;
		if (inline != lv.inline) {
			lv.inline = inline;
			if (lv.anim != null) lv.anim.cancel();
			lv.open = false;
			lv.row.setVisibility(View.GONE);
			lv.row.setAlpha(0f);
			lv.row.getLayoutParams().height = WRAP_CONTENT;
			ViewGroup from = (ViewGroup) levelRow.getParent();
			if (from != null) from.removeView(levelRow);
			if (inline) {
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(40));
				lp.setMarginEnd(dp(2));
				quick.addView(levelRow, 0, lp);
			} else {
				lv.row.addView(levelRow);
			}
			lv.toggle.setVisibility(inline ? View.GONE : View.VISIBLE);
		}
		for (int g : order) {
			if (g == G_LEVELS) continue;
			groups[g].label.setVisibility(((state & (1 << g)) != 0) ? View.VISIBLE : View.GONE);
		}
		syncToggles();
	}

	private boolean groupActive(int g) {
		switch (g) {
			case G_LEVELS:
				return minLevel != Level.DEBUG;
			case G_TIME:
				return (fromMs > 0) || (toMs > 0);
			case G_CATS:
				return !hidden.isEmpty();
			default:
				return classic || live || selectMode || !newestFirst;
		}
	}

	/** A toggle is filled with the accent while its filter changes the lines, outlined while its row is open. */
	private void syncToggles() {
		for (int g = 0; g < groups.length; g++) {
			Group x = groups[g];
			if (x == null) continue;
			boolean active = groupActive(g);
			x.fill.setColor(active ? accent : 0x1F808080);
			x.fill.setStroke(x.open ? dp(2) : 0, x.open ? (active ? onAccent : accent) : 0);
			int fg = active ? onAccent : textPrimary;
			x.label.setTextColor(fg);
			x.icon.setImageTintList(ColorStateList.valueOf(active ? onAccent : accent));
		}
	}

	private void toggleGroup(int g) {
		Group x = groups[g];
		x.open = !x.open;
		animateRow(x, x.open);
		syncToggles();
	}

	/** The row grows from nothing to its height, fading in (and back), with the panel following. */
	private void animateRow(Group x, boolean show) {
		if (x.anim != null) x.anim.cancel();
		LinearLayout row = x.row;
		ViewGroup.LayoutParams lp = row.getLayoutParams();
		int target;
		if (show) {
			row.setVisibility(View.VISIBLE);
			row.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, quick.getWidth()), View.MeasureSpec.EXACTLY),
					View.MeasureSpec.UNSPECIFIED);
			target = row.getMeasuredHeight();
		} else {
			target = 0;
		}
		int from = (lp.height > 0) ? lp.height : row.getHeight();
		float alphaFrom = row.getAlpha();
		rowAnims++;
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(220);
		a.setInterpolator(new android.view.animation.DecelerateInterpolator());
		a.addUpdateListener(v -> {
			float f = (float) v.getAnimatedValue();
			lp.height = Math.round(from + (target - from) * f);
			row.setLayoutParams(lp);
			row.setAlpha(alphaFrom + ((show ? 1f : 0f) - alphaFrom) * f);
		});
		final boolean[] done = new boolean[1];
		Runnable end = () -> {
			if (done[0]) return;
			done[0] = true;
			rowAnims--;
			if (x.anim == a) x.anim = null;
		};
		a.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationCancel(android.animation.Animator an) {
				end.run();
			}

			@Override
			public void onAnimationEnd(android.animation.Animator an) {
				if (!show && !x.open) row.setVisibility(View.GONE);
				if (!done[0]) {
					lp.height = WRAP_CONTENT;
					row.setLayoutParams(lp);
				}
				end.run();
				// The list's room under the panel catches up once, not on every frame.
				if ((rowAnims == 0) && (spacer != null) && (sticky != null)) {
					ViewGroup.LayoutParams sl = spacer.getLayoutParams();
					sl.height = sticky.getHeight() + dp(10);
					spacer.setLayoutParams(sl);
				}
			}
		});
		x.anim = a;
		a.start();
	}

	// ---- time range

	private static String stamp(long ms) {
		return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date(ms));
	}

	private void timeChanged() {
		buildDateChips();
		syncToggles();
		refresh(true, SCROLL_RESULTS);
	}

	private void buildDateChips() {
		dateChips.removeAllViews();
		long now = System.currentTimeMillis();
		addChip(dateChips, ChipFlowLayout.chip(palette, 0, R.string.diag_time_last_hour, false, () -> {
			fromMs = System.currentTimeMillis() - 3600_000L;
			toMs = 0;
			timeChanged();
		}));
		addChip(dateChips, ChipFlowLayout.chip(palette, 0, R.string.diag_time_last_6h, false, () -> {
			fromMs = System.currentTimeMillis() - 6 * 3600_000L;
			toMs = 0;
			timeChanged();
		}));
		addChip(dateChips, ChipFlowLayout.chip(palette, 0, R.string.diag_time_today, false, () -> {
			java.util.Calendar c = java.util.Calendar.getInstance();
			c.set(java.util.Calendar.HOUR_OF_DAY, 0);
			c.set(java.util.Calendar.MINUTE, 0);
			c.set(java.util.Calendar.SECOND, 0);
			c.set(java.util.Calendar.MILLISECOND, 0);
			fromMs = c.getTimeInMillis();
			toMs = 0;
			timeChanged();
		}));
		java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
		String from = (fromMs > 0) ? getString(R.string.diag_time_from, df.format(new java.util.Date(fromMs))) :
				getString(R.string.diag_time_from_any);
		String to = (toMs > 0) ? getString(R.string.diag_time_to, df.format(new java.util.Date(toMs))) :
				getString(R.string.diag_time_to_any);
		addChip(dateChips, toggleChip(from, fromMs > 0, () -> pickTime(true)));
		addChip(dateChips, toggleChip(to, toMs > 0, () -> pickTime(false)));
		if ((fromMs > 0) || (toMs > 0)) {
			addChip(dateChips, ChipFlowLayout.chip(palette, R.drawable.close_small, R.string.diag_time_clear, false, () -> {
				fromMs = 0;
				toMs = 0;
				timeChanged();
			}));
		}
		// Room for the last chip to scroll clear of the fade.
		dateChips.addView(new View(palette), new LinearLayout.LayoutParams(dp(24), 1));
	}

	/** The date, then the time, of one end of the range. */
	private void pickTime(boolean isFrom) {
		Context ctx = requireContext();
		java.util.Calendar c = java.util.Calendar.getInstance();
		long cur = isFrom ? fromMs : toMs;
		if (cur > 0) c.setTimeInMillis(cur);
		android.app.DatePickerDialog dd = new android.app.DatePickerDialog(ctx, (dv, y, m, d) -> {
			android.app.TimePickerDialog td = new android.app.TimePickerDialog(ctx, (tv, h, mi) -> {
				java.util.Calendar r = java.util.Calendar.getInstance();
				r.set(y, m, d, h, mi, isFrom ? 0 : 59);
				r.set(java.util.Calendar.MILLISECOND, isFrom ? 0 : 999);
				if (isFrom) fromMs = r.getTimeInMillis();
				else toMs = r.getTimeInMillis();
				if ((fromMs > 0) && (toMs > 0) && (fromMs > toMs)) {
					long t = fromMs;
					fromMs = toMs;
					toMs = t;
				}
				timeChanged();
			}, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
					android.text.format.DateFormat.is24HourFormat(ctx));
			styleDialog(td);
			td.show();
		}, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
		styleDialog(dd);
		dd.show();
	}

	/** The system pickers' OK and Cancel took a text colour that is the dialog's own dark: they get the accent. */
	private void styleDialog(android.app.AlertDialog d) {
		d.setOnShowListener(x -> {
			for (int which : new int[]{android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE}) {
				TextView b = d.getButton(which);
				if (b == null) continue;
				b.setTextColor(accent);
				b.setAllCaps(false);
				b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
				b.setTypeface(b.getTypeface(), Typeface.BOLD);
				b.setMinHeight(dp(44));
				b.setPadding(dp(16), 0, dp(16), 0);
			}
		});
	}

	private View buildSearch() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.HORIZONTAL);
		box.setGravity(Gravity.CENTER_VERTICAL);
		box.setPadding(dp(14), 0, dp(6), 0);
		box.setBackground(rounded(tileFill, 16));
		ImageView icon = new ImageView(palette);
		icon.setImageResource(R.drawable.search);
		icon.setImageTintList(ColorStateList.valueOf(textSecondary));
		box.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
		EditText edit = new EditText(palette);
		searchEdit = edit;
		edit.setBackground(null);
		edit.setHint(R.string.diag_search_hint);
		edit.setHintTextColor(ColorUtils.setAlphaComponent(textSecondary, 0xAA));
		edit.setTextColor(textPrimary);
		edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		edit.setSingleLine(true);
		edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		edit.setText(query);
		edit.setPadding(dp(10), dp(12), dp(6), dp(12));
		box.addView(edit, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView clear = new ImageView(palette);
		clear.setImageResource(R.drawable.close_small);
		clear.setImageTintList(ColorStateList.valueOf(textSecondary));
		clear.setPadding(dp(8), dp(8), dp(8), dp(8));
		clear.setBackground(pressable(rounded(0, 20)));
		clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
		clear.setOnClickListener(v -> edit.setText(""));
		box.addView(clear, new LinearLayout.LayoutParams(dp(40), dp(40)));
		edit.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				query = s.toString();
				clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
				if (searchRun != null) handler.removeCallbacks(searchRun);
				searchRun = DiagnosticLogFragment.this::refreshForSearch;
				handler.postDelayed(searchRun, SEARCH_DELAY_MS);
			}
		});
		edit.setOnEditorActionListener((v, action, ev) -> {
			if (action != EditorInfo.IME_ACTION_SEARCH) return false;
			android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
					v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
			v.clearFocus();
			return true;
		});
		return box;
	}

	private void buildViewChips() {
		viewChips.removeAllViews();
		addChip(viewChips, toggleChip(getString(R.string.diag_newest_first), newestFirst, () -> {
			newestFirst = !newestFirst;
			buildViewChips();
			applyFilter();
			adapter.notifyDataSetChanged();
			if (list != null) list.scrollToPosition(0);
		}));
		addChip(viewChips, toggleChip(getString(R.string.diag_live), live, () -> {
			live = !live;
			buildViewChips();
			startLive();
		}));
		addChip(viewChips, toggleChip(getString(R.string.diag_classic), classic, () -> {
			classic = !classic;
			buildViewChips();
			adapter.notifyDataSetChanged();
			if (list != null) list.scrollToPosition(0);
		}));
		addChip(viewChips, toggleChip(getString(R.string.diag_select), selectMode, () -> {
			setSelectMode(!selectMode);
			buildViewChips();
		}));
		viewChips.addView(new View(palette), new LinearLayout.LayoutParams(dp(24), 1));
		syncToggles();
	}

	private void buildCategoryChips() {
		categoryChips.removeAllViews();
		addChip(categoryChips, ChipFlowLayout.chip(palette, 0, R.string.diag_cat_all, false, () -> {
			hidden.clear();
			refresh(true);
			buildCategoryChips();
		}));
		addChip(categoryChips, ChipFlowLayout.chip(palette, 0, R.string.diag_cat_none, false, () -> {
			hidden.addAll(counts.keySet());
			refresh(true);
			buildCategoryChips();
		}));
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			String cat = e.getKey();
			boolean on = !hidden.contains(cat);
			String label = "● " + cat + "  " + e.getValue();
			TextView chip = toggleChip(label, on, () -> {
				if (!hidden.remove(cat)) hidden.add(cat);
				refresh(true);
				buildCategoryChips();
			});
			// Hold: only this one.
			chip.setOnLongClickListener(v -> {
				hidden.clear();
				hidden.addAll(counts.keySet());
				hidden.remove(cat);
				refresh(true);
				buildCategoryChips();
				return true;
			});
			// The dot is the category's colour, as on its lines.
			SpannableString sp = new SpannableString(label);
			sp.setSpan(new ForegroundColorSpan(on ? onAccent : categoryColor(cat)), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			chip.setText(sp);
			addChip(categoryChips, chip);
		}
		categoryChips.addView(new View(palette), new LinearLayout.LayoutParams(dp(24), 1));
		syncToggles();
	}

	/** A chip that is filled with the accent while on. */
	private TextView toggleChip(String label, boolean on, Runnable click) {
		TextView t = ChipFlowLayout.chip(palette, 0, label, on, click);
		t.setTypeface(t.getTypeface(), on ? Typeface.BOLD : Typeface.NORMAL);
		return t;
	}

	private void syncHeader() {
		if (header == null) return;
		if (stickyCount != null) stickyCount.setText(getString(R.string.diag_count_short, filtered.size(), all.size()));
		boolean on = DiagnosticLog.isEnabled();
		recordingSub.setText(on ? R.string.diag_recording_on : R.string.diag_recording_off);
		if (recordingSwitch.isChecked() != on) {
			syncingSwitch = true;
			recordingSwitch.setChecked(on);
			syncingSwitch = false;
		}
		syncLevel();
	}

	private void syncLevel() {
		if (levelRow == null) return;
		Level[] levels = Level.values();
		for (int i = 0; i < levelRow.getChildCount(); i++) {
			TextView t = (TextView) levelRow.getChildAt(i);
			boolean on = levels[i] == minLevel;
			t.setTextColor(on ? onAccent : textPrimary);
			((GradientDrawable) t.getTag(R.id.settings_row_tag)).setColor(on ? accent : 0x1F808080);
		}
	}

	private void addBlock(LinearLayout parent, View v, int topDp) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(topDp);
		parent.addView(v, lp);
	}

	private void section(LinearLayout parent, @StringRes int title) {
		TextView t = text(getString(title), 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		t.setPadding(dp(4), dp(20), 0, dp(6));
		parent.addView(t);
	}

	// ---------------------------------------------------------------------------------------------
	// The lines

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Override
		public int getItemViewType(int position) {
			if (position == 0) return TYPE_HEADER;
			if (shown.isEmpty()) return TYPE_EMPTY;
			return classic ? TYPE_CLASSIC : TYPE_ROW;
		}

		@Override
		public int getItemCount() {
			return 1 + ((shown.isEmpty() || classic) ? 1 : shown.size());
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if (viewType == TYPE_HEADER) {
				// The header is built once; detach it from the old parent if the list asks again.
				if (header.getParent() instanceof ViewGroup g) g.removeView(header);
				return new RecyclerView.ViewHolder(header) {
				};
			}
			if (viewType == TYPE_EMPTY) return new RecyclerView.ViewHolder(emptyView()) {
			};
			if (viewType == TYPE_CLASSIC) return new RecyclerView.ViewHolder(classicView()) {
			};
			return new RowHolder();
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			if (h instanceof RowHolder r) r.bind(shown.get(position - 1));
			else if (getItemViewType(position) == TYPE_EMPTY) bindEmpty(h.itemView);
			else if (getItemViewType(position) == TYPE_CLASSIC) bindClassic((TextView) h.itemView);
		}
	}

	/** The classic look: one block of text, as it was before, that can be selected and copied by hand. */
	private View classicView() {
		TextView t = text("", 12, textSecondary, false);
		t.setTypeface(Typeface.MONOSPACE);
		t.setTextIsSelectable(true);
		t.setPadding(dp(4), dp(10), dp(4), dp(10));
		t.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return t;
	}

	private void bindClassic(TextView t) {
		StringBuilder sb = new StringBuilder(shown.size() * 80);
		for (Entry e : shown) sb.append(e.toLine()).append('\n');
		t.setText(sb);
	}

	private View emptyView() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);
		box.setPadding(dp(16), dp(28), dp(16), dp(28));
		box.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		TextView t = text("", 14, textSecondary, false);
		t.setGravity(Gravity.CENTER);
		t.setTag("text");
		box.addView(t);
		TextView reset = ChipFlowLayout.chip(palette, 0, R.string.diag_reset_filters, false, () -> {
			minLevel = Level.DEBUG;
			hidden.clear();
			query = "";
			if (searchEdit != null) searchEdit.setText("");
			buildCategoryChips();
			refresh(true);
		});
		resetChip = reset;
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.topMargin = dp(14);
		box.addView(reset, lp);
		return box;
	}

	private void bindEmpty(View v) {
		TextView t = v.findViewWithTag("text");
		if (t == null) return;
		if (all.isEmpty()) {
			t.setText(DiagnosticLog.isEnabled() ? R.string.diagnostic_log_empty : R.string.diagnostic_log_disabled);
			if (resetChip != null) resetChip.setVisibility(View.GONE);
		} else {
			t.setText(R.string.diag_nothing_matches);
			if (resetChip != null) resetChip.setVisibility(filtersActive() ? View.VISIBLE : View.GONE);
		}
	}

	private final class RowHolder extends RecyclerView.ViewHolder {
		private final View bar;
		private final TextView time;
		private final TextView cat;
		private final TextView level;
		private final TextView message;
		private final TextView details;
		private final TextView stack;
		private Entry entry;

		RowHolder() {
			super(new LinearLayout(palette));
			LinearLayout row = (LinearLayout) itemView;
			row.setOrientation(LinearLayout.HORIZONTAL);
			RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			rlp.topMargin = dp(6);
			row.setLayoutParams(rlp);
			row.setBackground(pressable(rounded(tileFill, 12)));
			row.setClickable(true);
			row.setFocusable(true);
			bar = new View(palette);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(4), MATCH_PARENT);
			blp.setMargins(dp(6), dp(6), 0, dp(6));
			row.addView(bar, blp);

			LinearLayout body = new LinearLayout(palette);
			body.setOrientation(LinearLayout.VERTICAL);
			body.setPadding(dp(10), dp(8), dp(12), dp(9));
			LinearLayout top = new LinearLayout(palette);
			top.setOrientation(LinearLayout.HORIZONTAL);
			top.setGravity(Gravity.CENTER_VERTICAL);
			time = text("", 11, textSecondary, false);
			time.setTypeface(Typeface.MONOSPACE);
			top.addView(time);
			cat = text("", 10.5f, textPrimary, true);
			cat.setPadding(dp(7), dp(1), dp(7), dp(1));
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			clp.setMarginStart(dp(8));
			top.addView(cat, clp);
			level = text("", 10.5f, textPrimary, true);
			LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			llp.setMarginStart(dp(8));
			top.addView(level, llp);
			body.addView(top);
			message = text("", 14, textPrimary, true);
			message.setPadding(0, dp(3), 0, 0);
			body.addView(message);
			details = text("", 12, textSecondary, false);
			details.setTypeface(Typeface.MONOSPACE);
			details.setPadding(0, dp(2), 0, 0);
			body.addView(details);
			stack = text("", 11, textSecondary, false);
			stack.setTypeface(Typeface.MONOSPACE);
			stack.setPadding(0, dp(4), 0, 0);
			body.addView(stack);
			row.addView(body, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

			row.setOnLongClickListener(v -> {
				// Hold a line to start picking lines; hold again to pick or drop one.
				if (entry == null) return true;
				if (!selectMode) setSelectMode(true);
				toggle(entry, this);
				return true;
			});
			row.setOnClickListener(v -> {
				if (selectMode) {
					if (entry != null) toggle(entry, this);
					return;
				}
				// A tap spreads or folds a long line.
				boolean fold = message.getMaxLines() != Integer.MAX_VALUE;
				message.setMaxLines(fold ? Integer.MAX_VALUE : 3);
				details.setMaxLines(fold ? Integer.MAX_VALUE : 4);
			});
		}

		void bind(Entry e) {
			entry = e;
			GradientDrawable rowFill = picked.contains(e) ? rounded(ColorUtils.setAlphaComponent(accent, 0x30), 12) :
					rounded(tileFill, 12);
			if (picked.contains(e)) rowFill.setStroke(dp(2), accent);
			itemView.setBackground(pressable(rowFill));
			int levelColor = levelColor(e.level);
			GradientDrawable b = rounded(levelColor, 2);
			bar.setBackground(b);
			time.setText(e.clock());
			int cc = categoryColor(e.category);
			cat.setText(e.category);
			cat.setTextColor(cc);
			cat.setBackground(rounded(ColorUtils.setAlphaComponent(cc, 0x2B), 6));
			if (e.level == Level.WARN || e.level == Level.ERROR) {
				level.setVisibility(View.VISIBLE);
				level.setText(e.level == Level.ERROR ? "ERROR" : "WARN");
				level.setTextColor(levelColor);
			} else {
				level.setVisibility(View.GONE);
			}
			message.setMaxLines(3);
			message.setEllipsize(android.text.TextUtils.TruncateAt.END);
			message.setText(e.message);
			details.setMaxLines(4);
			details.setEllipsize(android.text.TextUtils.TruncateAt.END);
			if (e.details.isEmpty()) {
				details.setVisibility(View.GONE);
			} else {
				details.setVisibility(View.VISIBLE);
				details.setText(highlightKeys(e.details));
			}
			if (e.extra.isEmpty()) {
				stack.setVisibility(View.GONE);
			} else {
				stack.setVisibility(View.VISIBLE);
				stack.setText(e.extra);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Picking lines

	private void setSelectMode(boolean on) {
		selectMode = on;
		if (!on) picked.clear();
		syncSelection();
		adapter.notifyDataSetChanged();
	}

	private void toggle(Entry e, RowHolder h) {
		if (!picked.remove(e)) picked.add(e);
		syncSelection();
		int pos = h.getBindingAdapterPosition();
		if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos);
	}

	private void syncSelection() {
		if (selectBar == null) return;
		selectBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
		if (selectCount != null) selectCount.setText(getString(R.string.diag_selected_count, picked.size()));
		if ((header != null) && (viewChips != null)) buildViewChips();
	}

	/** The picked lines, oldest first. */
	private List<Entry> pickedLines() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : all) if (picked.contains(e)) out.add(e);
		return out;
	}

	private void copyPicked() {
		List<Entry> l = pickedLines();
		if (l.isEmpty()) return;
		copyText(DiagnosticLog.export(l, all.size(), "picked lines"), R.string.diag_picked_copied);
	}

	private void sharePicked() {
		List<Entry> l = pickedLines();
		if (l.isEmpty()) return;
		shareText(DiagnosticLog.export(l, all.size(), "picked lines"));
	}

	/** The bar that holds the picking buttons, floating over the list. */
	private LinearLayout buildSelectBar() {
		LinearLayout bar = new LinearLayout(palette);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setPadding(dp(14), dp(8), dp(10), dp(8));
		GradientDrawable bg = rounded(MenuStyle.panelColor(palette), 24);
		bar.setBackground(bg);
		bar.setElevation(dp(8));
		selectCount = text("", 14, textPrimary, true);
		bar.addView(selectCount);
		ChipFlowLayout chips = new ChipFlowLayout(palette);
		chips.addView(ChipFlowLayout.chip(palette, R.drawable.copy, R.string.diagnostic_log_copy, true, this::copyPicked));
		chips.addView(ChipFlowLayout.chip(palette, R.drawable.share, R.string.diagnostic_log_share, false, this::sharePicked));
		chips.addView(ChipFlowLayout.chip(palette, 0, R.string.diag_select_all, false, () -> {
			picked.addAll(shown);
			syncSelection();
			adapter.notifyDataSetChanged();
		}));
		chips.addView(ChipFlowLayout.chip(palette, 0, R.string.diag_select_done, false, () -> setSelectMode(false)));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.setMarginStart(dp(12));
		bar.addView(chips, lp);
		bar.setVisibility(View.GONE);
		return bar;
	}

	@Override
	public boolean onBackPressed() {
		if (selectMode) {
			setSelectMode(false);
			return true;
		}
		return super.onBackPressed();
	}

	/** The {@code key=} of every {@code key=value} in the accent, so the facts stand out from their values. */
	private CharSequence highlightKeys(String details) {
		SpannableString s = new SpannableString(details);
		int i = 0;
		int n = details.length();
		boolean inQuote = false;
		int tokenStart = 0;
		boolean atStart = true;
		for (; i < n; i++) {
			char c = details.charAt(i);
			if (c == '"') inQuote = !inQuote;
			if (atStart) {
				tokenStart = i;
				atStart = false;
			}
			if (!inQuote && c == '=') {
				// A key: from the token's start to here (only before its first '=').
				if (isKey(details, tokenStart, i)) {
					s.setSpan(new ForegroundColorSpan(accent), tokenStart, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
					s.setSpan(new StyleSpan(Typeface.BOLD), tokenStart, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
				}
			}
			if (!inQuote && c == ' ') atStart = true;
		}
		return s;
	}

	private static boolean isKey(String s, int from, int to) {
		if (to <= from) return false;
		for (int i = from; i < to; i++) {
			char c = s.charAt(i);
			if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) return false;
		}
		return true;
	}

	private int levelColor(Level l) {
		return switch (l) {
			case DEBUG -> ColorUtils.setAlphaComponent(textSecondary, 0xAA);
			case INFO -> accent;
			case WARN -> 0xFFFFA000;
			case ERROR -> 0xFFE53935;
		};
	}

	/** The same colour for the same category every time, light enough on the dark themes and deep enough on the light. */
	private int categoryColor(String category) {
		int hue = Math.abs(category.hashCode() * 31) % 360;
		return Color.HSVToColor(new float[]{hue, lightTheme ? 0.75f : 0.5f, lightTheme ? 0.62f : 0.98f});
	}

	// ---------------------------------------------------------------------------------------------
	// The buttons

	private String exportText() {
		return DiagnosticLog.export(filtered, all.size(), filterText());
	}

	private void copy() {
		if (filtered.isEmpty()) return;
		copyText(exportText(), R.string.diagnostic_log_copied);
	}

	private void copyText(String text, @StringRes int done) {
		// getActivityDelegate().getContext() rather than this Fragment's own getContext(): this app's Activity/Fragment
		// glue is custom enough (MainCarActivity isn't even a real Activity subclass) that the two aren't guaranteed to
		// be interchangeable.
		Context ctx = getActivityDelegate().getContext();
		boolean copied;
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null) return;
			cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostic_log), text));
			copied = true;
		} catch (Exception err) {
			// A payload this large is exactly what can trip TransactionTooLargeException crossing the Binder call into
			// the system clipboard service.
			Log.e(err, "Failed to copy the diagnostic log");
			copied = false;
		}
		if (copied) TopToast.show(R.drawable.copy, done);
		else TopToast.show(R.drawable.copy, R.string.diagnostic_log_copy_failed);
	}

	private void share() {
		if (filtered.isEmpty()) return;
		shareText(exportText());
	}

	private void shareText(String text) {
		if (text.length() > MAX_SHARE_CHARS) text = text.substring(text.length() - MAX_SHARE_CHARS);
		try {
			// EXTRA_TEXT rather than a file + FileProvider: the provider is only declared in the Auto build's manifest,
			// and the payload is small enough that a plain text share works from every build and needs no grant
			// plumbing.
			Intent i = new Intent(Intent.ACTION_SEND);
			i.setType("text/plain");
			i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostic_log) + " " + DiagnosticLog.appVersion());
			i.putExtra(Intent.EXTRA_TEXT, text);
			startActivity(Intent.createChooser(i, getString(R.string.diagnostic_log_share)));
		} catch (Exception err) {
			Log.e(err, "Failed to share the diagnostic log");
			UiUtils.showAlert(getActivityDelegate().getContext(), String.valueOf(err.getLocalizedMessage()));
		}
	}

	private void clear() {
		UiUtils.showQuestion(getActivityDelegate().getContext(), R.string.diag_clear_title, R.string.diag_clear_question,
				R.drawable.delete).onSuccess(v -> {
			DiagnosticLog.clear();
			refresh(true);
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers

	private int color(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(float v) {
		return Math.round(v * density);
	}

	private TextView text(CharSequence s, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private GradientDrawable rounded(int color, float radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(radiusDp));
		return d;
	}

	private RippleDrawable pressable(GradientDrawable fill) {
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(0xFF000000);
		mask.setCornerRadius(fill.getCornerRadius());
		return new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask);
	}
}
