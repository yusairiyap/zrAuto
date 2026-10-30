package me.aap.fermata.ui.welcome;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.Key;
import me.aap.fermata.action.KeyTester;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.data.DataSessionLimit;
import me.aap.fermata.addon.data.DataUsageAddon;
import me.aap.fermata.addon.data.DataUsageTracker;
import me.aap.fermata.addon.power.PowerSaver;
import me.aap.fermata.addon.power.PowerUsageAddon;
import me.aap.fermata.backup.BackupManager;
import me.aap.fermata.ui.activity.BackDetour;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.ActionFabMediator;
import me.aap.fermata.ui.fragment.SettingsFragment;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.SwitchStyle;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.app.App;
import me.aap.utils.pref.PreferenceStore;

/**
 * The welcome and setup wizard: the first thing a new install shows, and always there again under
 * Settings &gt; Other. A full screen over the app, a page at a time.
 * <p>
 * First the <b>welcome</b>: the best of what zrAuto does, each with a little animated picture
 * ({@link WelcomeArtView}). Then the <b>setup</b>: bring a backup back or switch automatic backups
 * on; whether the phone will be used with Android Auto, and if so the car settings people turn on
 * first and a live simulator for the steering wheel keys (press a real one or tap a key here: it
 * shows what the app sees and what it does, and the action can be changed on the spot); a few
 * settings for everyone; and a last page that welcomes them and offers the full Settings.
 * <p>
 * Every page lays out as picture above text on a phone and picture beside text where the screen is
 * wide (a tablet, a phone on its side), inside a column that never gets wider than a tablet's
 * comfortable reading width.
 */
public final class WelcomeWizard {
	/** The wizard was gone through, or skipped: it does not come up by itself again. */
	/** Written by the application when it starts with empty preferences; stays until the wizard is done. */
	public static final String FRESH_KEY = "WELCOME_FRESH";
	public static final PreferenceStore.Pref<me.aap.utils.function.BooleanSupplier> DONE =
			PreferenceStore.Pref.b("WELCOME_DONE", false);
	private static final long LONG_PRESS_MS = 600;
	private static final long DOUBLE_PRESS_MS = 350;
	@Nullable
	private static WelcomeWizard open;

	private final MainActivityDelegate a;
	private final Context pc;
	private final ViewGroup host;
	private final float density;
	private final int textPrimary;
	private final int textSecondary;
	private final int textTertiary;
	private final int rowFill;
	private final int cardFill;
	private final int chipFill;
	private final int track;
	private final int accent;
	private final int onAccent;
	private final int background;
	private final List<Page> pages = new ArrayList<>();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private FrameLayout overlay;
	private PageHost pageHost;
	private Progress progress;
	private TextView phase;
	private TextView skip;
	private TextView left;
	private TextView primary;
	@Nullable
	private PageLayout current;
	// A page being swiped or slid in, and where (index) it is from.
	@Nullable
	private PageLayout incoming;
	private int incomingIndex = -1;
	@Nullable
	private ValueAnimator settle;
	@Nullable
	private FrameLayout picker;
	private int index = -1;
	private boolean carChoice;
	private boolean animating;
	private boolean closing;
	// Stepped aside for a detour, see detour().
	private boolean hidden;
	// The key simulator page.
	private final KeyView[] keyViews = new KeyView[4];
	private final long[] downAt = new long[4];
	private final long[] lastUpAt = new long[4];
	private final Runnable[] longChecks = new Runnable[4];
	private final List<TextView> bindingChips = new ArrayList<>();
	@Nullable
	private TextView heroTitle;
	@Nullable
	private TextView heroDetail;
	@Nullable
	private TextView backupFolder;

	private WelcomeWizard(MainActivityDelegate a) {
		this.a = a;
		this.pc = EffectsUi.palette(a.getContext());
		this.host = a.findViewById(R.id.main_activity);
		this.density = pc.getResources().getDisplayMetrics().density;
		textPrimary = color(R.attr.musicTextPrimary);
		textSecondary = color(R.attr.musicTextSecondary);
		textTertiary = color(R.attr.musicTextTertiary);
		rowFill = color(R.attr.musicRowFill);
		// An opaque surface: the row and chip fills are see-through tints meant to sit on the
		// background, and made opaque they came out as a light card on dark and a dark one on light.
		cardFill = color(R.attr.musicCardFill);
		chipFill = color(R.attr.musicChipFill);
		track = color(R.attr.musicSeekTrack);
		background = color(R.attr.musicBackground);
		accent = EffectsUi.accent(pc);
		onAccent = EffectsUi.onAccent(accent);
		buildPages();
	}

	// ---------------------------------------------------------------------------------------------
	// Entry points

	/**
	 * Called when the app opens: shows the wizard on a fresh install, once. An app that was only
	 * updated (it had been used before the wizard existed) is marked as done without showing it.
	 */
	public static void maybeShow(@NonNull MainActivityDelegate a) {
		maybeShow(a, 0);
	}

	private static void maybeShow(@NonNull MainActivityDelegate a, int attempt) {
		MainActivityPrefs p = a.getPrefs();
		boolean done = p.getBooleanPref(DONE);
		boolean fresh = isFreshInstall(a.getContext());
		if (done || isOpen() || a.isCarActivity()) {
			runAfter();
			return;
		}
		if (!fresh) {
			p.applyBooleanPref(DONE, true);
			runAfter();
			return;
		}
		show(a, true);
		if (!isOpen()) {
			// No view to put it over yet (the window is not laid out): tried again very soon, a good few times, rather than never.
			if (attempt < 40) a.postDelayed(() -> maybeShow(a, attempt + 1), 150);
			else runAfter();
		}
	}

	/** What waits for the wizard to be over (the start-up permission dialogs, which would cover it). */
	private static Runnable afterWizard;

	/** Runs {@code r} once the wizard is closed, or at once if it is not going to come. */
	public static void afterWizard(@NonNull Runnable r) {
		afterWizard = r;
	}

	private static void runAfter() {
		Runnable r = afterWizard;
		afterWizard = null;
		if (r != null) r.run();
	}

	/**
	 * Whether the wizard is still to come: a fresh install that has not been through it. Anything
	 * that would put a system dialog up at start-up (a permission) waits for it.
	 */
	public static boolean isDue(@NonNull MainActivityDelegate a) {
		return !a.getPrefs().getBooleanPref(DONE) && !a.isCarActivity() && isFreshInstall(a.getContext());
	}

	/**
	 * Whether the app starts with no data of its own: installed just now and never updated since, or with its data cleared
	 * (an install that is older, with the preferences found empty at start, see FermataApplication#attachBaseContext).
	 */
	private static boolean isFreshInstall(Context ctx) {
		try {
			if (ctx.getSharedPreferences("fermata", Context.MODE_PRIVATE).getBoolean(FRESH_KEY, false)) return true;
		} catch (RuntimeException ignore) {
			// Falls back to the install times.
		}
		try {
			PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
			return Math.abs(pi.lastUpdateTime - pi.firstInstallTime) < 2 * 60_000L;
		} catch (Exception ex) {
			return false;
		}
	}

	/** Shows the wizard from its first page (Settings &gt; Other, or a new install). */
	public static void show(@NonNull MainActivityDelegate a) {
		show(a, false);
	}

	/** {@code instant}: the wizard is there in full from the first frame (the start of a fresh install), not faded in. */
	private static void show(@NonNull MainActivityDelegate a, boolean instant) {
		if (isOpen()) {
			// Waiting out a detour: asked for again, it comes back.
			if (open.hidden) open.reveal();
			return;
		}
		ViewGroup host = a.findViewById(R.id.main_activity);
		if ((host == null) || (host.getWidth() == 0)) {
			DiagnosticLog.log(LogCat.WELCOME, "no view to show the wizard over");
			return;
		}
		WelcomeWizard w = new WelcomeWizard(a);
		open = w;
		w.build(instant);
	}

	/**
	 * Steps aside while {@code show} puts another screen up (the key settings): the wizard waits,
	 * unseen, and is back on the page it left when Back is pressed there.
	 */
	private void detour(int targetId, Runnable show) {
		a.getPrefs().applyBooleanPref(DONE, true);
		BackDetour.start(targetId, this::reveal, () -> close(true));
		hidden = true;
		stopKeySimulator();
		overlay.animate().cancel();
		overlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
			if (hidden) overlay.setVisibility(View.GONE);
		}).start();
		show.run();
	}

	/** Back from the detour: the wizard fades back in where it was. */
	private void reveal() {
		if (!hidden) return;
		hidden = false;
		overlay.animate().cancel();
		overlay.setVisibility(View.VISIBLE);
		overlay.animate().alpha(1f).setDuration(260).start();
		List<Page> act = active();
		if ((index >= 0) && (index < act.size()) && (act.get(index).onEnter != null)) act.get(index).onEnter.run();
	}

	/** Whether the wizard is on screen; one left over from a screen that has gone is forgotten. */
	private static boolean isOpen() {
		WelcomeWizard w = open;
		if (w == null) return false;
		if ((w.overlay != null) && (w.overlay.getParent() != null) && !w.closing) return true;
		w.stopKeySimulator();
		open = null;
		return false;
	}

	/** Back: the picker if one is up, else the page before, else the wizard closes. */
	public static boolean onBack() {
		if (!isOpen()) return false;
		WelcomeWizard w = open;
		if ((w == null) || w.hidden) return false;
		if (w.picker != null) {
			w.closePicker();
			return true;
		}
		if (w.restored) return true; // Nothing to go back to: it only waits for the restart.
		if (w.index > 0) w.go(w.index - 1);
		else w.close(false);
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Pages

	private static final class Page {
		final int scene;
		@StringRes
		final int title;
		@StringRes
		final int text;
		final boolean setup;
		final boolean car;
		final int[] bullets;
		@Nullable
		Consumer<LinearLayout> body;
		@Nullable
		Runnable onEnter;
		@Nullable
		Runnable onLeave;
		@StringRes
		int primary;

		Page(int scene, int title, int text, boolean setup, boolean car, int... bullets) {
			this.scene = scene;
			this.title = title;
			this.text = text;
			this.setup = setup;
			this.car = car;
			this.bullets = bullets;
		}
	}

	private Page add(int scene, int title, int text, boolean setup, boolean car, int... bullets) {
		Page p = new Page(scene, title, text, setup, car, bullets);
		pages.add(p);
		return p;
	}

	private void buildPages() {
		Page p = add(WelcomeArtView.INTRO, R.string.wz_intro_title, R.string.wz_intro_text, false, false,
				R.string.wz_intro_b1, R.string.wz_intro_b2, R.string.wz_intro_b3);
		p.primary = R.string.wz_start;
		add(WelcomeArtView.DOWNLOAD, R.string.wz_download_title, R.string.wz_download_text, false, false,
				R.string.wz_download_b1, R.string.wz_download_b2, R.string.wz_download_b3);
		add(WelcomeArtView.SPOTIFY, R.string.wz_spotify_title, R.string.wz_spotify_text, false, false,
				R.string.wz_spotify_b1, R.string.wz_spotify_b2, R.string.wz_spotify_b3);
		add(WelcomeArtView.CAR, R.string.wz_car_title, R.string.wz_car_text, false, false,
				R.string.wz_car_b1, R.string.wz_car_b2, R.string.wz_car_b3);
		add(WelcomeArtView.LYRICS, R.string.wz_lyrics_title, R.string.wz_lyrics_text, false, false,
				R.string.wz_lyrics_b1, R.string.wz_lyrics_b2, R.string.wz_lyrics_b3);
		add(WelcomeArtView.EFFECTS, R.string.wz_effects_title, R.string.wz_effects_text, false, false,
				R.string.wz_effects_b1, R.string.wz_effects_b2, R.string.wz_effects_b3);
		add(WelcomeArtView.ALL_IN_ONE, R.string.wz_aio_title, R.string.wz_aio_text, false, false,
				R.string.wz_aio_b1, R.string.wz_aio_b2, R.string.wz_aio_b3);
		add(WelcomeArtView.AMBIENCE, R.string.wz_amb_title, R.string.wz_amb_text, false, false,
				R.string.wz_amb_b1, R.string.wz_amb_b2, R.string.wz_amb_b3);
		p = add(WelcomeArtView.MORE, R.string.wz_more_title, R.string.wz_more_text, false, false,
				R.string.wz_more_b1, R.string.wz_more_b2, R.string.wz_more_b3);
		p.primary = R.string.wz_setup;

		p = add(WelcomeArtView.BACKUP, R.string.wz_backup_title, R.string.wz_backup_text, true, false);
		p.body = this::backupBody;
		p = add(WelcomeArtView.CAR_ASK, R.string.wz_ask_title, R.string.wz_ask_text, true, false);
		p.body = this::askBody;
		p = add(WelcomeArtView.CAR, R.string.wz_carset_title, R.string.wz_carset_text, true, true);
		p.body = this::carSettingsBody;
		p = add(WelcomeArtView.KEYS, R.string.wz_keys_title, R.string.wz_keys_text, true, true);
		p.body = this::keysBody;
		p.onEnter = this::startKeySimulator;
		p.onLeave = this::stopKeySimulator;
		p = add(WelcomeArtView.TUNE, R.string.wz_tune_title, R.string.wz_tune_text, true, false);
		p.body = this::tuneBody;
		p = add(WelcomeArtView.DONE, R.string.wz_done_title, R.string.wz_done_text, true, false);
		p.primary = R.string.wz_finish;
		// Only shown after a restore, then alone: see showRestored().
		restoredPage = add(WelcomeArtView.DONE, R.string.wz_restored_title, R.string.wz_restored_text, true, false);
		restoredPage.primary = R.string.wz_restart;
	}

	/** The last page of a restore from a backup: the app has to restart before the restored settings are used. */
	private Page restoredPage;
	private boolean restored;

	/** The pages in use now: the car ones only once the user said they use Android Auto. */
	private List<Page> active() {
		List<Page> l = new ArrayList<>(pages.size());
		if (restored) {
			l.add(restoredPage);
			return l;
		}
		for (Page p : pages) if (((p != restoredPage)) && (!p.car || carChoice)) l.add(p);
		return l;
	}

	// ---------------------------------------------------------------------------------------------
	// The frame

	private void build(boolean instant) {
		overlay = new FrameLayout(pc);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		// Above the bars and the menus (10dp), below the top cards (26dp): a toast still shows.
		overlay.setElevation(dp(24));
		overlay.setAlpha(0f);
		// The pictures' glow reaches past their own edges: nothing along the way may cut it.
		overlay.setClipChildren(false);

		WelcomeBackdropView backdrop = new WelcomeBackdropView(pc);
		backdrop.setColors(background, accent, 0xFF5B8DEF);
		overlay.addView(backdrop, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		Column column = new Column();
		column.setOrientation(LinearLayout.VERTICAL);
		// Neither children nor the padding (the system bars' room) may cut the glow, or the pages
		// while they slide: both clipped at the column's own edge.
		column.setClipChildren(false);
		column.setClipToPadding(false);
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT,
				Gravity.CENTER_HORIZONTAL);
		overlay.addView(column, clp);

		// Top: which half we are in, how far, and a way out.
		LinearLayout top = new LinearLayout(pc);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		top.setPadding(dp(20), dp(12), dp(12), dp(4));
		phase = text("", 12, accent, true);
		phase.setAllCaps(true);
		phase.setLetterSpacing(0.08f);
		top.addView(phase, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		progress = new Progress();
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(6), 1f);
		plp.setMargins(dp(14), 0, dp(10), 0);
		top.addView(progress, plp);
		skip = text(pc.getString(R.string.wz_skip), 14, textSecondary, true);
		skip.setPadding(dp(14), dp(10), dp(14), dp(10));
		skip.setBackground(pressable(0, 20));
		skip.setClickable(true);
		skip.setFocusable(true);
		skip.setOnClickListener(v -> skip());
		top.addView(skip, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		column.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		pageHost = new PageHost();
		pageHost.setClipChildren(false);
		column.addView(pageHost, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		LinearLayout bottom = new LinearLayout(pc);
		bottom.setOrientation(LinearLayout.HORIZONTAL);
		bottom.setGravity(Gravity.CENTER_VERTICAL);
		bottom.setPadding(dp(20), dp(8), dp(20), dp(16));
		left = text("", 15, textSecondary, true);
		left.setGravity(Gravity.CENTER);
		left.setPadding(dp(18), dp(12), dp(18), dp(12));
		left.setBackground(pressable(0, 24));
		left.setClickable(true);
		left.setFocusable(true);
		bottom.addView(left, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)));
		bottom.addView(new View(pc), new LinearLayout.LayoutParams(0, 1, 1f));
		primary = text("", 16, onAccent, true);
		primary.setGravity(Gravity.CENTER);
		primary.setSingleLine(true);
		primary.setMinWidth(dp(150));
		primary.setPadding(dp(28), 0, dp(28), 0);
		primary.setBackground(pressableFilled(accent, 26));
		primary.setClickable(true);
		primary.setFocusable(true);
		primary.setOnClickListener(v -> next());
		bottom.addView(primary, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(52)));
		column.addView(bottom, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The system bars are drawn over the app: keep clear of them.
		ViewCompat.setOnApplyWindowInsetsListener(overlay, (v, insets) -> {
			Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() |
					WindowInsetsCompat.Type.displayCutout());
			column.setPadding(i.left, i.top, i.right, i.bottom);
			return insets;
		});

		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		ViewCompat.requestApplyInsets(overlay);
		// Where the insets never arrive (the host handles them itself): the status bar's height.
		overlay.post(() -> {
			if (column.getPaddingTop() == 0) {
				int id = pc.getResources().getIdentifier("status_bar_height", "dimen", "android");
				if (id != 0) column.setPadding(0, pc.getResources().getDimensionPixelSize(id), 0, 0);
			}
		});

		if (instant) overlay.setAlpha(1f);
		else overlay.animate().alpha(1f).setDuration(320).start();
		go(0);
	}

	/** A column no wider than a tablet reads comfortably in. */
	private final class Column extends LinearLayout {
		Column() {
			super(pc);
		}

		@Override
		protected void onMeasure(int w, int h) {
			int max = dp(880);
			if (MeasureSpec.getSize(w) > max) w = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
			super.onMeasure(w, h);
		}
	}

	/**
	 * Goes to page {@code to}: the pages morph into each other, the one leaving shrinking and fading
	 * away from the one arriving, the picture and the text sliding at different speeds. The same
	 * motion follows a finger when the pages are swiped, see {@link PageHost}.
	 */
	private void go(int to) {
		List<Page> act = active();
		if (animating || (to < 0) || (to >= act.size()) || (to == index)) return;
		if (index < 0) {
			// The first page: it comes up where it is.
			index = to;
			Page p = act.get(to);
			PageLayout view = buildPage(p);
			pageHost.addView(view, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			current = view;
			updateChrome(p, act.size());
			if (p.onEnter != null) p.onEnter.run();
			view.setAlpha(0f);
			view.setScaleX(0.94f);
			view.setScaleY(0.94f);
			animating = true;
			view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
					.setInterpolator(new DecelerateInterpolator(1.6f)).withEndAction(() -> animating = false).start();
			stagger(view);
			return;
		}
		int dir = (to > index) ? 1 : -1;
		if (!prepareIncoming(to)) return;
		morph(0f, dir);
		settleTo(0f, dir, to, true, true);
	}

	/** Builds the page to arrive and puts it ready, off to the side. */
	private boolean prepareIncoming(int target) {
		List<Page> act = active();
		if ((target < 0) || (target >= act.size())) return false;
		if ((incoming != null) && (incomingIndex == target)) return true;
		discardIncoming();
		PageLayout view = buildPage(act.get(target));
		pageHost.addView(view, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		view.setAlpha(0f);
		incoming = view;
		incomingIndex = target;
		return true;
	}

	private void discardIncoming() {
		if (incoming != null) pageHost.removeView(incoming);
		incoming = null;
		incomingIndex = -1;
	}

	/**
	 * Lays both pages out for a drag of {@code tx} pixels (negative: to the left, towards the next
	 * page): the leaving one follows the finger, shrinking a little and fading out within the first
	 * 60% of the drag; the arriving one comes in from the side, fading in from a quarter of the way and
	 * growing to its place.
	 */
	private void morph(float tx, int dir) {
		int w = Math.max(1, pageHost.getWidth());
		float p = Math.min(1f, Math.abs(tx) / w);
		// The leaving page is gone well before it has left (no ghost of it over the arriving one), the
		// arriving one only shows once the other has mostly faded.
		float out = 1f - smooth(p / 0.6f);
		float in = smooth((p - 0.25f) / 0.75f);
		if (current != null) current.morph(tx, out, 1f - 0.06f * p);
		if (incoming != null) incoming.morph(tx + dir * w, in, 0.94f + 0.06f * p);
	}

	/** {@code x} held to 0..1 and eased at both ends. */
	private static float smooth(float x) {
		x = Math.max(0f, Math.min(1f, x));
		return x * x * (3f - 2f * x);
	}

	/**
	 * Carries the pages on from {@code from} pixels of drag to {@code end} (the arriving page in
	 * place when {@code commit}, else back where they were).
	 */
	private void settleTo(float from, int dir, int target, boolean commit, boolean animateBody) {
		if (settle != null) settle.cancel();
		float end = commit ? ((dir > 0) ? -pageHost.getWidth() : pageHost.getWidth()) : 0f;
		animating = true;
		if (commit) {
			// The page's own state is taken over as soon as it is decided: the bar, the buttons.
			List<Page> act = active();
			Page old = act.get(Math.min(index, act.size() - 1));
			if (old.onLeave != null) old.onLeave.run();
			index = target;
			Page p = act.get(target);
			updateChrome(p, act.size());
			if (p.onEnter != null) p.onEnter.run();
		}
		ValueAnimator a = ValueAnimator.ofFloat(from, end);
		a.setDuration(Math.max(180, Math.min(420, Math.round(Math.abs(end - from) / Math.max(1, pageHost.getWidth()) * 420))));
		a.setInterpolator(new DecelerateInterpolator(1.8f));
		a.addUpdateListener(v -> morph((float) v.getAnimatedValue(), dir));
		a.addListener(new android.animation.AnimatorListenerAdapter() {
			private boolean cancelled;

			@Override
			public void onAnimationCancel(android.animation.Animator animation) {
				cancelled = true;
			}

			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (cancelled) return;
				if (commit) {
					if (current != null) pageHost.removeView(current);
					current = incoming;
					incoming = null;
					incomingIndex = -1;
					if (current != null) current.morph(0f, 1f, 1f);
				} else {
					discardIncoming();
					if (current != null) current.morph(0f, 1f, 1f);
				}
				animating = false;
				settle = null;
			}
		});
		settle = a;
		a.start();
		if (commit && animateBody && (incoming != null)) stagger(incoming);
	}

	/** The page's own elements arrive one after the other. */
	private void stagger(View page) {
		View body = page.findViewWithTag("body");
		if (body instanceof ViewGroup bg) {
			for (int i = 0; i < bg.getChildCount(); i++) {
				View c = bg.getChildAt(i);
				c.setAlpha(0f);
				c.setTranslationY(dp(16));
				c.animate().alpha(1f).translationY(0f).setStartDelay(160 + 70L * i).setDuration(380)
						.setInterpolator(new DecelerateInterpolator(1.5f)).start();
			}
		}
	}

	/**
	 * The pages' area: a horizontal drag anywhere on it swipes to the next or the previous page,
	 * the pages following the finger and morphing as they go, and carried on (or back) when let
	 * go, by how far they were dragged or how hard they were flung. Past the first and the last
	 * page the drag gives only a little.
	 */
	private final class PageHost extends FrameLayout {
		private final int slop = android.view.ViewConfiguration.get(pc).getScaledTouchSlop();
		private float downX;
		private float downY;
		private boolean dragging;
		@Nullable
		private android.view.VelocityTracker velocity;
		private int dir;

		PageHost() {
			super(pc);
		}

		@Override
		public boolean onInterceptTouchEvent(MotionEvent e) {
			if (animating || (picker != null)) return false;
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					downX = e.getX();
					downY = e.getY();
					dragging = false;
					if (velocity != null) velocity.recycle();
					velocity = android.view.VelocityTracker.obtain();
					velocity.addMovement(e);
				}
				case MotionEvent.ACTION_MOVE -> {
					if (velocity != null) velocity.addMovement(e);
					float dx = e.getX() - downX;
					float dy = e.getY() - downY;
					if (!dragging && (Math.abs(dx) > slop) && (Math.abs(dx) > Math.abs(dy) * 1.3f)) {
						dragging = true;
						if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
						return true;
					}
				}
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					dragging = false;
					if (velocity != null) velocity.recycle();
					velocity = null;
				}
			}
			return dragging;
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if (!dragging) {
				// Nothing under the finger took the touch (the picture on the left): the area is the target itself, and the
				// moves come straight here, never through onInterceptTouchEvent, so the swipe starts here too.
				if (animating || (picker != null)) return super.onTouchEvent(e);
				switch (e.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						downX = e.getX();
						downY = e.getY();
						if (velocity != null) velocity.recycle();
						velocity = android.view.VelocityTracker.obtain();
						velocity.addMovement(e);
						return true;
					}
					case MotionEvent.ACTION_MOVE -> {
						if (velocity == null) return false;
						velocity.addMovement(e);
						float mx = e.getX() - downX;
						float my = e.getY() - downY;
						if ((Math.abs(mx) > slop) && (Math.abs(mx) > Math.abs(my) * 1.3f)) {
							dragging = true;
							if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
							drag(mx);
						}
						return true;
					}
					case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						if (velocity != null) velocity.recycle();
						velocity = null;
						return true;
					}
				}
				return super.onTouchEvent(e);
			}
			if (velocity != null) velocity.addMovement(e);
			float dx = e.getX() - downX;
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_MOVE -> drag(dx);
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					float vx = 0;
					if (velocity != null) {
						velocity.computeCurrentVelocity(1000);
						vx = velocity.getXVelocity();
						velocity.recycle();
						velocity = null;
					}
					dragging = false;
					release(dx, (e.getActionMasked() == MotionEvent.ACTION_CANCEL) ? 0 : vx);
				}
			}
			return true;
		}

		private void drag(float dx) {
			int w = Math.max(1, getWidth());
			dir = (dx < 0) ? 1 : -1;
			int target = index + dir;
			boolean can = (target >= 0) && (target < active().size());
			if (can) prepareIncoming(target);
			else discardIncoming();
			// Past the ends it only gives a little, and springs back.
			float tx = can ? dx : dx * 0.22f;
			morph(Math.max(-w, Math.min(w, tx)), dir);
		}

		private void release(float dx, float vx) {
			int w = Math.max(1, getWidth());
			int d = (dx < 0) ? 1 : -1;
			int target = index + d;
			boolean can = (target >= 0) && (target < active().size()) && (incoming != null);
			float tx = can ? Math.max(-w, Math.min(w, dx)) : dx * 0.22f;
			boolean flung = (Math.abs(vx) > 900) && (Math.signum(vx) == Math.signum(dx));
			boolean commit = can && ((Math.abs(tx) > w * 0.28f) || flung);
			settleTo(tx, d, target, commit, false);
		}
	}

	/** Skip: out of the introduction on to the setup; out of the setup, the end. */
	private void skip() {
		List<Page> act = active();
		if (index >= 0) {
			Page cur = act.get(Math.min(index, act.size() - 1));
			if (!cur.setup) {
				for (int i = 0; i < act.size(); i++) {
					if (act.get(i).setup) {
						go(i);
						return;
					}
				}
			}
		}
		close(false);
	}

	private void next() {
		if (restored) {
			me.aap.fermata.util.Utils.restartApp(a.getContext());
			return;
		}
		if (index >= active().size() - 1) {
			close(true);
			return;
		}
		go(index + 1);
	}

	private void updateChrome(Page p, int count) {
		phase.setText(p.setup ? R.string.wz_phase_setup : R.string.wz_phase_welcome);
		progress.setFraction((index + 1f) / count);
		boolean last = index == count - 1;
		primary.setText(p.primary != 0 ? p.primary : R.string.wz_next);
		skip.setText(p.setup ? R.string.wz_skip : R.string.wz_skip_intro);
		skip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
		if (restored) {
			left.setVisibility(View.INVISIBLE);
		} else if (last) {
			left.setText(R.string.wz_more_settings);
			left.setVisibility(View.VISIBLE);
			left.setOnClickListener(v -> {
				close(true);
				a.exitVideoMode();
				a.showFragment(R.id.settings_fragment);
			});
		} else if (index > 0) {
			left.setText(R.string.wz_back);
			left.setVisibility(View.VISIBLE);
			left.setOnClickListener(v -> go(index - 1));
		} else {
			left.setVisibility(View.INVISIBLE);
		}
	}

	private void close(boolean finished) {
		if (closing) return;
		closing = true;
		stopKeySimulator();
		closePicker();
		if (settle != null) settle.cancel();
		a.getPrefs().applyBooleanPref(DONE, true);
		DiagnosticLog.log(LogCat.WELCOME, "wizard ended", "how=" + (finished ? "finished" : "closed"), "page=" + index);
		runAfter();
		if (hidden) {
			// Out of sight already (a detour that did not come back): nothing to fade.
			hidden = false;
			host.removeView(overlay);
			if (open == this) open = null;
			return;
		}
		overlay.animate().alpha(0f).setDuration(260).withEndAction(() -> {
			host.removeView(overlay);
			if (open == this) open = null;
		}).start();
	}

	// ---------------------------------------------------------------------------------------------
	// One page

	/** Picture above the text on a phone, picture beside it where the screen is wide. */
	private final class PageLayout extends LinearLayout {
		private final View art;
		private final View body;

		PageLayout(View art, View body) {
			super(pc);
			this.art = art;
			this.body = body;
			setOrientation(VERTICAL);
			setClipChildren(false);
			addView(art, new LayoutParams(MATCH_PARENT, 0));
			addView(body, new LayoutParams(MATCH_PARENT, 0, 1f));
		}

		/**
		 * Shows the page {@code tx} pixels along, faded and scaled as given. The text moves with the
		 * page and the picture a little behind it, so the two seem to be on different layers.
		 */
		void morph(float tx, float alpha, float scale) {
			setTranslationX(tx);
			setAlpha(alpha);
			setScaleX(scale);
			setScaleY(scale);
			art.setTranslationX(-tx * 0.35f);
			art.setAlpha(alpha);
		}

		@Override
		protected void onMeasure(int wSpec, int hSpec) {
			int w = MeasureSpec.getSize(wSpec);
			int h = MeasureSpec.getSize(hSpec);
			boolean wide = (w / density >= 600) && (w > h * 1.05f);
			int want = wide ? HORIZONTAL : VERTICAL;
			if (getOrientation() != want) setOrientation(want);
			LayoutParams alp = (LayoutParams) art.getLayoutParams();
			LayoutParams blp = (LayoutParams) body.getLayoutParams();
			if (wide) {
				alp.width = 0;
				alp.height = MATCH_PARENT;
				alp.weight = 0.9f;
				blp.width = 0;
				blp.height = MATCH_PARENT;
				blp.weight = 1.1f;
			} else {
				alp.width = MATCH_PARENT;
				alp.height = Math.max(dp(150), Math.min(dp(300), Math.round(h * 0.34f)));
				alp.weight = 0f;
				blp.width = MATCH_PARENT;
				blp.height = 0;
				blp.weight = 1f;
			}
			super.onMeasure(wSpec, hSpec);
		}
	}

	/**
	 * A page's text: it scrolls behind the picture and fades out at the top. The page is laid out with
	 * clipChildren off (so the picture's glow is not cut), which would let the scrolled text draw over
	 * the picture, so this view clips its own content to its bounds.
	 */
	private static final class PageScroll extends ScrollView {
		PageScroll(Context ctx) {
			super(ctx);
		}

		@Override
		protected void dispatchDraw(Canvas canvas) {
			canvas.save();
			canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
			super.dispatchDraw(canvas);
			canvas.restore();
		}

		@Override
		protected float getBottomFadingEdgeStrength() {
			return 0f;
		}
	}

	private PageLayout buildPage(Page p) {
		WelcomeArtView art = new WelcomeArtView(pc);
		art.setColors(accent, onAccent, cardFill, textPrimary, textSecondary, track);
		art.setScene(p.scene);

		ScrollView scroll = new PageScroll(pc);
		scroll.setFillViewport(true);
		scroll.setVerticalScrollBarEnabled(false);
		// The text melts into the picture's background at the top instead of cutting off over it.
		scroll.setVerticalFadingEdgeEnabled(true);
		scroll.setFadingEdgeLength(dp(40));
		LinearLayout body = new LinearLayout(pc);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setTag("body");
		body.setPadding(dp(24), dp(10), dp(24), dp(16));
		scroll.addView(body, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView title = text(pc.getString(p.title), 28, textPrimary, true);
		title.setLineSpacing(0, 1.02f);
		body.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		TextView sub = text(pc.getString(p.text), 16, textSecondary, false);
		sub.setLineSpacing(dp(2), 1f);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		slp.topMargin = dp(8);
		body.addView(sub, slp);
		if (p.bullets.length > 0) {
			LinearLayout list = new LinearLayout(pc);
			list.setOrientation(LinearLayout.VERTICAL);
			for (int b : p.bullets) list.addView(bullet(b));
			LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			llp.topMargin = dp(14);
			body.addView(list, llp);
		}
		if (p.body != null) p.body.accept(body);
		return new PageLayout(art, scroll);
	}

	private View bullet(@StringRes int res) {
		LinearLayout row = new LinearLayout(pc);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.TOP);
		ImageView tick = new ImageView(pc);
		tick.setImageResource(R.drawable.done);
		tick.setImageTintList(ColorStateList.valueOf(accent));
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
		ilp.topMargin = dp(2);
		row.addView(tick, ilp);
		TextView t = text(pc.getString(res), 15, textPrimary, false);
		t.setLineSpacing(dp(1), 1f);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		tlp.setMarginStart(dp(10));
		row.addView(t, tlp);
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		rlp.topMargin = dp(8);
		row.setLayoutParams(rlp);
		return row;
	}

	// ---------------------------------------------------------------------------------------------
	// Setup: backup and restore

	private void backupBody(LinearLayout body) {
		PreferenceStore store = FermataApplication.get().getPreferenceStore();

		LinearLayout restore = card();
		restore.addView(text(pc.getString(R.string.wz_restore_title), 16, textPrimary, true));
		TextView rs = text(pc.getString(R.string.wz_restore_text), 13, textSecondary, false);
		rs.setPadding(0, dp(4), 0, dp(12));
		restore.addView(rs);
		restore.addView(button(R.drawable.save, R.string.wz_restore_button, this::restoreBackup),
				new LinearLayout.LayoutParams(MATCH_PARENT, dp(48)));
		body.addView(restore, cardParams(18));

		LinearLayout auto = card();
		auto.addView(switchRow(R.drawable.refresh, R.string.wz_autobackup, R.string.wz_autobackup_sub,
				() -> store.getBooleanPref(BackupManager.AUTO_BACKUP),
				on -> store.applyBooleanPref(BackupManager.AUTO_BACKUP, on)));
		TextView every = text(pc.getString(R.string.wz_backup_every), 13, textSecondary, false);
		every.setPadding(0, dp(12), 0, dp(6));
		auto.addView(every);
		auto.addView(chips(new int[]{R.string.auto_backup_interval_1, R.string.auto_backup_interval_3,
						R.string.auto_backup_interval_7, R.string.auto_backup_interval_30},
				() -> store.getIntPref(BackupManager.AUTO_BACKUP_INTERVAL),
				i -> store.applyIntPref(BackupManager.AUTO_BACKUP_INTERVAL, i)));
		TextView where = text(pc.getString(R.string.wz_backup_where), 13, textSecondary, false);
		where.setPadding(0, dp(12), 0, dp(2));
		auto.addView(where);
		backupFolder = text(BackupManager.getDestinationName(pc), 14, textPrimary, true);
		backupFolder.setSingleLine(true);
		backupFolder.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		auto.addView(backupFolder);
		LinearLayout buttons = new LinearLayout(pc);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setPadding(0, dp(12), 0, 0);
		LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
		b1.setMarginEnd(dp(6));
		buttons.addView(button(R.drawable.add_folder, R.string.wz_choose_folder, () ->
				SettingsFragment.pickBackupFolder(a, () -> {
					if (backupFolder != null) backupFolder.setText(BackupManager.getDestinationName(pc));
				})), b1);
		LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
		b2.setMarginStart(dp(6));
		buttons.addView(button(R.drawable.save, R.string.wz_backup_now, this::backupNow), b2);
		auto.addView(buttons);
		body.addView(auto, cardParams(12));
	}

	private void restoreBackup() {
		// A restored backup brings its own settings back, this wizard's state among them: after the
		// restart it must not start over.
		a.getPrefs().applyBooleanPref(DONE, true);
		boolean saf = me.aap.fermata.util.Utils.isSafSupported(a);
		if (!saf) close(true);
		SettingsFragment.importPrefs(a, saf ? this::showRestored : null);
	}

	/** The restore went through: the wizard ends on the page that says the app restarts. */
	private void showRestored() {
		restored = true;
		stopKeySimulator();
		closePicker();
		if (animating) {
			handler.postDelayed(() -> go(0), 450);
		} else {
			go(0);
		}
	}

	private void backupNow() {
		Context ctx = a.getContext();
		TopToast.show(R.drawable.save, R.string.wz_backing_up);
		new Thread(() -> {
			String name = BackupManager.runBackup(ctx);
			App.get().getHandler().post(() -> {
				if (name != null) TopToast.show(R.drawable.save, R.string.auto_backup_done, name);
				else TopToast.show(R.drawable.save, R.string.auto_backup_failed);
			});
			BackupManager.schedule(ctx);
		}, "backup-now").start();
	}

	// ---------------------------------------------------------------------------------------------
	// Setup: Android Auto

	private void askBody(LinearLayout body) {
		LinearLayout row = new LinearLayout(pc);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setBaselineAligned(false);
		View[] cards = new View[2];
		cards[0] = choiceCard(R.drawable.car, R.string.wz_ask_yes, R.string.wz_ask_yes_sub, carChoice);
		cards[1] = choiceCard(R.drawable.phone, R.string.wz_ask_no, R.string.wz_ask_no_sub, !carChoice);
		for (int i = 0; i < 2; i++) {
			boolean yes = i == 0;
			cards[i].setOnClickListener(v -> {
				carChoice = yes;
				setChoice(cards[0], carChoice);
				setChoice(cards[1], !carChoice);
				// The steps after this one come and go with the answer.
				progress.setFraction((index + 1f) / active().size());
				DiagnosticLog.log(LogCat.WELCOME, "android auto answered", "yes=" + yes);
			});
			// Both as tall as the taller one.
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
			lp.setMarginStart(i == 0 ? 0 : dp(6));
			lp.setMarginEnd(i == 0 ? dp(6) : 0);
			row.addView(cards[i], lp);
		}
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		rlp.topMargin = dp(18);
		body.addView(row, rlp);
		TextView hint = text(pc.getString(R.string.wz_ask_hint), 13, textTertiary, false);
		hint.setPadding(dp(4), dp(12), dp(4), 0);
		body.addView(hint);
	}

	private View choiceCard(@DrawableRes int icon, @StringRes int title, @StringRes int sub, boolean selected) {
		LinearLayout c = new LinearLayout(pc);
		c.setOrientation(LinearLayout.VERTICAL);
		c.setGravity(Gravity.CENTER);
		c.setPadding(dp(14), dp(18), dp(14), dp(18));
		c.setClickable(true);
		c.setFocusable(true);
		ImageView iv = new ImageView(pc);
		iv.setImageResource(icon);
		iv.setTag("icon");
		c.addView(iv, new LinearLayout.LayoutParams(dp(34), dp(34)));
		TextView t = text(pc.getString(title), 16, textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setTag("title");
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		tlp.topMargin = dp(10);
		c.addView(t, tlp);
		TextView s = text(pc.getString(sub), 12, textSecondary, false);
		s.setGravity(Gravity.CENTER);
		s.setTag("sub");
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		slp.topMargin = dp(4);
		c.addView(s, slp);
		setChoice(c, selected);
		return c;
	}

	private void setChoice(View card, boolean selected) {
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(20));
		bg.setColor(selected ? alpha(accent, 0.18f) : alpha(cardFill, 0.94f));
		bg.setStroke(dp(selected ? 2 : 1), selected ? accent : alpha(textSecondary, 0.25f));
		card.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(accent, 0.25f)), bg, null));
		((ImageView) card.findViewWithTag("icon")).setImageTintList(
				ColorStateList.valueOf(selected ? accent : textSecondary));
		if (selected) {
			card.animate().cancel();
			card.setScaleX(0.96f);
			card.setScaleY(0.96f);
			card.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(2f)).start();
		}
	}

	private void carSettingsBody(LinearLayout body) {
		LinearLayout list = card();
		MainActivityPrefs mp = a.getPrefs();
		PreferenceStore keys = Key.getPrefs();
		list.addView(switchRow(R.drawable.car, R.string.wz_car_mode, R.string.wz_car_mode_sub,
				() -> keys.getBooleanPref(Key.CAR_MODE), on -> keys.applyBooleanPref(Key.CAR_MODE, on)));
		list.addView(divider());
		list.addView(switchRow(R.drawable.keyboard, R.string.wz_bind_transport, R.string.wz_bind_transport_sub,
				() -> keys.getBooleanPref(Key.BIND_TRANSPORT),
				on -> keys.applyBooleanPref(Key.BIND_TRANSPORT, on)));
		if (BuildConfig.AUTO && (MainActivityPrefs.SCROLL_HIDE_BARS_AA != null)) {
			list.addView(divider());
			list.addView(switchRow(R.drawable.expand, R.string.wz_car_bars, R.string.wz_car_bars_sub,
					() -> mp.getBooleanPref(MainActivityPrefs.SCROLL_HIDE_BARS_AA),
					on -> mp.applyBooleanPref(MainActivityPrefs.SCROLL_HIDE_BARS_AA, on)));
		}
		if (PowerUsageAddon.isEnabled()) {
			PreferenceStore ps = FermataApplication.get().getPreferenceStore();
			list.addView(divider());
			list.addView(switchRow(R.drawable.power_saver, R.string.wz_car_saver, R.string.wz_car_saver_sub,
					() -> ps.getBooleanPref(PowerSaver.ON_CAR), on -> ps.applyBooleanPref(PowerSaver.ON_CAR, on)));
		}
		if (AddonManager.get().getAddon(DataUsageAddon.class) != null) {
			PreferenceStore ps = FermataApplication.get().getPreferenceStore();
			list.addView(divider());
			list.addView(switchRow(R.drawable.data_usage, R.string.wz_car_data, R.string.wz_car_data_sub,
					() -> ps.getBooleanPref(DataSessionLimit.ENABLED),
					on -> ps.applyBooleanPref(DataSessionLimit.ENABLED, on)));
		}
		body.addView(list, cardParams(16));
		TextView hint = text(pc.getString(R.string.wz_carset_hint), 13, textTertiary, false);
		hint.setPadding(dp(4), dp(12), dp(4), 0);
		body.addView(hint);
	}

	// ---------------------------------------------------------------------------------------------
	// Setup: the steering wheel keys

	private static final Key[] SIM_KEYS = {Key.MEDIA_PREVIOUS, Key.MEDIA_PLAY_PAUSE, Key.MEDIA_NEXT,
			Key.VOICE_ASSIST};
	private static final int[] SIM_ICONS = {R.drawable.prev, R.drawable.play_pause, R.drawable.next,
			R.drawable.record_voice};
	private static final int[] SIM_LABELS = {R.string.key_label_previous, R.string.key_label_play_pause,
			R.string.key_label_next, R.string.key_label_voice};

	private void keysBody(LinearLayout body) {
		bindingChips.clear();
		// What the app just saw.
		LinearLayout hero = card();
		hero.setGravity(Gravity.CENTER_HORIZONTAL);
		heroTitle = text(pc.getString(R.string.wz_keys_idle), 20, textPrimary, true);
		heroTitle.setGravity(Gravity.CENTER);
		hero.addView(heroTitle);
		heroDetail = text(pc.getString(R.string.wz_keys_idle_sub), 14, textSecondary, false);
		heroDetail.setGravity(Gravity.CENTER);
		heroDetail.setPadding(0, dp(4), 0, 0);
		hero.addView(heroDetail);
		body.addView(hero, cardParams(14));

		// The keys, to press for real or to tap.
		LinearLayout row = new LinearLayout(pc);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER);
		for (int i = 0; i < SIM_KEYS.length; i++) {
			KeyView kv = new KeyView(i);
			keyViews[i] = kv;
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins(dp(4), 0, dp(4), 0);
			row.addView(kv, lp);
		}
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		rlp.topMargin = dp(14);
		body.addView(row, rlp);

		// What they do: tap a chip to change it.
		LinearLayout binds = card();
		TextView bt = text(pc.getString(R.string.wz_keys_bindings), 15, textPrimary, true);
		bt.setPadding(0, 0, 0, dp(6));
		binds.addView(bt);
		for (int i = 0; i < 3; i++) binds.addView(bindingRow(i));
		TextView note = text(pc.getString(R.string.wz_keys_note), 12, textTertiary, false);
		note.setPadding(0, dp(10), 0, 0);
		binds.addView(note);
		body.addView(binds, cardParams(14));

		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(46));
		blp.topMargin = dp(12);
		body.addView(button(R.drawable.settings, R.string.wz_keys_all, () -> detour(R.id.settings_fragment, () -> {
			a.exitVideoMode();
			a.showFragment(R.id.settings_fragment, SettingsFragment.SHOW_KEY_BINDINGS);
		})), blp);
	}

	/** Press or hold chips of one key. */
	private View bindingRow(int i) {
		Key k = SIM_KEYS[i];
		LinearLayout row = new LinearLayout(pc);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, dp(4), 0, dp(4));
		TextView name = text(pc.getString(SIM_LABELS[i]), 14, textPrimary, true);
		row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f));
		for (int kind = 0; kind < 2; kind++) {
			int fk = kind;
			TextView chip = text("", 12.5f, textPrimary, true);
			chip.setGravity(Gravity.CENTER);
			chip.setSingleLine(true);
			chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
			chip.setPadding(dp(10), dp(8), dp(10), dp(8));
			chip.setBackground(pressableFilled(chipFill, 18));
			chip.setClickable(true);
			chip.setFocusable(true);
			chip.setOnClickListener(v -> showActionPicker(k, fk, chip));
			chip.setTag(new int[]{i, kind});
			updateChip(chip);
			bindingChips.add(chip);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins(dp(4), 0, 0, 0);
			row.addView(chip, lp);
		}
		return row;
	}

	private Action bound(Key k, int kind) {
		return (kind == 0) ? k.getClickAction() : k.getLongClickAction();
	}

	private void updateChip(TextView chip) {
		int[] t = (int[]) chip.getTag();
		Action act = bound(SIM_KEYS[t[0]], t[1]);
		String what = (act == null) ? pc.getString(R.string.action_none) :
				pc.getString(ActionFabMediator.labelFor(a, act));
		chip.setText(pc.getString((t[1] == 0) ? R.string.wz_keys_press : R.string.wz_keys_hold, what));
	}

	/** A card to pick what a key does, over the page. */
	private void showActionPicker(Key k, int kind, TextView chip) {
		closePicker();
		FrameLayout scrim = new FrameLayout(pc);
		scrim.setBackgroundColor(0x99000000);
		scrim.setClickable(true);
		scrim.setOnClickListener(v -> closePicker());

		LinearLayout card = new LinearLayout(pc);
		card.setOrientation(LinearLayout.VERTICAL);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(26));
		card.setBackground(bg);
		card.setClickable(true);
		card.setPadding(dp(8), dp(12), dp(8), dp(8));
		TextView title = text(pc.getString(kind == 0 ? R.string.wz_pick_press : R.string.wz_pick_hold,
				pc.getString(SIM_LABELS[indexOf(k)])), 17, textPrimary, true);
		title.setPadding(dp(14), dp(4), dp(14), dp(10));
		card.addView(title);
		ScrollView sv = new ScrollView(pc);
		LinearLayout list = new LinearLayout(pc);
		list.setOrientation(LinearLayout.VERTICAL);
		sv.addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		Action current = bound(k, kind);
		for (Action act : Action.getAll()) {
			// Only what a key can usefully do (a tab that is not there is left out).
			if ((act == Action.POWER_USAGE || act == Action.POWER_SAVER) && !PowerUsageAddon.isEnabled()) continue;
			if ((act == Action.AMBIENCE || act == Action.AMBIENCE_TOGGLE) &&
					!me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled()) continue;
			LinearLayout item = new LinearLayout(pc);
			item.setOrientation(LinearLayout.HORIZONTAL);
			item.setGravity(Gravity.CENTER_VERTICAL);
			item.setPadding(dp(14), dp(10), dp(14), dp(10));
			boolean sel = act == current;
			item.setBackground(pressable(sel ? alpha(accent, 0.2f) : 0, 16));
			item.setClickable(true);
			item.setFocusable(true);
			ImageView iv = new ImageView(pc);
			iv.setImageResource(ActionFabMediator.iconFor(a, act));
			iv.setImageTintList(ColorStateList.valueOf(sel ? accent : textSecondary));
			item.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
			TextView t = text(pc.getString(ActionFabMediator.labelFor(a, act)), 15, sel ? accent : textPrimary, sel);
			LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			tlp.setMarginStart(dp(14));
			item.addView(t, tlp);
			item.setOnClickListener(v -> {
				PreferenceStore ps = Key.getPrefs();
				ps.applyIntPref((kind == 0) ? k.getActionPref() : k.getLongActionPref(), act.ordinal());
				for (TextView c : bindingChips) updateChip(c);
				closePicker();
				DiagnosticLog.log(LogCat.WELCOME, "key binding", "key=" + k.name(), "kind=" + (kind == 0 ? "press" : "hold"), "action=" + act.name());
			});
			list.addView(item, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		}
		card.addView(sv, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		int maxH = Math.round(host.getHeight() * 0.78f);
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
				Math.min(dp(420), host.getWidth() - dp(32)), maxH, Gravity.CENTER);
		scrim.addView(card, clp);
		overlay.addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		picker = scrim;
		scrim.setAlpha(0f);
		card.setTranslationY(dp(40));
		scrim.animate().alpha(1f).setDuration(200).start();
		card.animate().translationY(0f).setDuration(280).setInterpolator(new DecelerateInterpolator(1.6f)).start();
	}

	private static int indexOf(Key k) {
		for (int i = 0; i < SIM_KEYS.length; i++) if (SIM_KEYS[i] == k) return i;
		return 0;
	}

	private void closePicker() {
		FrameLayout p = picker;
		if (p == null) return;
		picker = null;
		p.animate().alpha(0f).setDuration(160).withEndAction(() -> overlay.removeView(p)).start();
	}

	/** One key of the simulator: a keycap that lights while pressed, whether for real or by touch. */
	private final class KeyView extends LinearLayout {
		private final int idx;
		private final ImageView icon;
		private final GradientDrawable cap = new GradientDrawable();

		@android.annotation.SuppressLint("ClickableViewAccessibility")
		KeyView(int idx) {
			super(pc);
			this.idx = idx;
			setOrientation(VERTICAL);
			setGravity(Gravity.CENTER_HORIZONTAL);
			cap.setCornerRadius(dp(18));
			cap.setColor(cardFill);
			cap.setStroke(dp(1), alpha(textSecondary, 0.25f));
			FrameLayout capView = new FrameLayout(pc);
			capView.setBackground(cap);
			capView.setElevation(dp(3));
			icon = new ImageView(pc);
			icon.setImageResource(SIM_ICONS[idx]);
			icon.setImageTintList(ColorStateList.valueOf(textPrimary));
			capView.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
			addView(capView, new LayoutParams(MATCH_PARENT, dp(60)));
			TextView label = text(pc.getString(SIM_LABELS[idx]), 11.5f, textSecondary, false);
			label.setGravity(Gravity.CENTER);
			label.setSingleLine(true);
			label.setPadding(0, dp(6), 0, 0);
			addView(label);
			setClickable(true);
			setOnTouchListener((v, e) -> {
				switch (e.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						keyDown(idx, true);
						return true;
					}
					case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						keyUp(idx);
						return true;
					}
				}
				return true;
			});
		}

		void light(boolean on) {
			cap.setColor(on ? accent : cardFill);
			icon.setImageTintList(ColorStateList.valueOf(on ? onAccent : textPrimary));
			animate().cancel();
			animate().scaleX(on ? 0.92f : 1f).scaleY(on ? 0.92f : 1f).setDuration(on ? 90 : 220)
					.setInterpolator(on ? new DecelerateInterpolator() : new OvershootInterpolator(3f)).start();
		}
	}

	private final KeyTester.Listener keyListener = new KeyTester.Listener() {
		@Override
		public boolean onKeyEvent(KeyEvent e, boolean session) {
			int code = e.getKeyCode();
			// Back still goes back, and anything that is no key of the app's is left alone.
			if ((code == KeyEvent.KEYCODE_BACK) || (code == KeyEvent.KEYCODE_ESCAPE)) return false;
			Key k = Key.get(code);
			if (k == null) return false;
			int i = simIndex(k);
			if (e.getAction() == KeyEvent.ACTION_DOWN) {
				if (e.getRepeatCount() == 0) {
					if (i >= 0) keyDown(i, false);
					else showOther(k, true);
				}
			} else if (e.getAction() == KeyEvent.ACTION_UP) {
				if (i >= 0) keyUp(i);
				else showOther(k, false);
			}
			return true;
		}

		@Override
		public boolean onTransport(KeyTester.Transport t) {
			// A command from Android Auto or a head unit, which is how many cars send their buttons.
			int i = simIndex(t.key);
			if (i >= 0) {
				keyDown(i, false);
				handler.postDelayed(() -> keyUp(i), 160);
			}
			return true; // never let it play or skip while testing
		}
	};

	private static int simIndex(Key k) {
		for (int i = 0; i < SIM_KEYS.length; i++) if (SIM_KEYS[i] == k) return i;
		// The skip keys some wheels send are the same buttons as Previous and Next.
		if (k == Key.MEDIA_SKIP_BACKWARD) return 0;
		if (k == Key.MEDIA_SKIP_FORWARD) return 2;
		if (k == Key.MEDIA_PLAY || k == Key.MEDIA_PAUSE || k == Key.HEADSETHOOK) return 1;
		if (k == Key.SEARCH) return 3;
		return -1;
	}

	private void startKeySimulator() {
		KeyTester.setListener(keyListener);
	}

	private void stopKeySimulator() {
		if (KeyTester.isActive()) KeyTester.setListener(null);
		for (int i = 0; i < longChecks.length; i++) {
			if (longChecks[i] != null) handler.removeCallbacks(longChecks[i]);
			downAt[i] = 0;
		}
	}

	private void keyDown(int i, boolean touch) {
		if (downAt[i] != 0) return;
		downAt[i] = SystemClock.uptimeMillis();
		if (keyViews[i] != null) keyViews[i].light(true);
		if (touch) keyViews[i].performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
		showHero(i, pc.getString(R.string.wz_keys_pressing));
		Runnable check = () -> {
			if (downAt[i] != 0) showHero(i, pc.getString(R.string.wz_keys_holding));
		};
		longChecks[i] = check;
		handler.postDelayed(check, LONG_PRESS_MS);
	}

	private void keyUp(int i) {
		if (downAt[i] == 0) return;
		long now = SystemClock.uptimeMillis();
		long held = now - downAt[i];
		downAt[i] = 0;
		if (longChecks[i] != null) handler.removeCallbacks(longChecks[i]);
		if (keyViews[i] != null) keyViews[i].light(false);
		Key k = SIM_KEYS[i];
		int kind;
		if (held >= LONG_PRESS_MS) kind = 2;
		else if ((lastUpAt[i] != 0) && (now - lastUpAt[i] <= DOUBLE_PRESS_MS)) kind = 1;
		else kind = 0;
		lastUpAt[i] = (kind == 2) ? 0 : now;
		Action act = (kind == 0) ? k.getClickAction() : (kind == 1) ? k.getDblClickAction() : k.getLongClickAction();
		String what = (act == null || act == Action.NONE) ? pc.getString(R.string.wz_keys_nothing) :
				pc.getString(ActionFabMediator.labelFor(a, act));
		String type = pc.getString(kind == 0 ? R.string.wz_keys_type_press : kind == 1 ?
				R.string.wz_keys_type_double : R.string.wz_keys_type_hold);
		showHero(i, pc.getString(R.string.wz_keys_result, type, what));
	}

	private void showHero(int i, String detail) {
		TextView t = heroTitle;
		TextView d = heroDetail;
		if ((t == null) || (d == null)) return;
		t.setText(SIM_LABELS[i]);
		d.setText(detail);
		t.animate().cancel();
		t.setScaleX(1.12f);
		t.setScaleY(1.12f);
		t.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
	}

	private void showOther(Key k, boolean down) {
		TextView t = heroTitle;
		TextView d = heroDetail;
		if ((t == null) || (d == null)) return;
		String s = KeyEvent.keyCodeToString(k.getCode());
		if (s.startsWith("KEYCODE_")) s = s.substring(8);
		t.setText(s.replace('_', ' '));
		Action act = k.getClickAction();
		d.setText(down ? pc.getString(R.string.wz_keys_pressing) : pc.getString(R.string.wz_keys_result,
				pc.getString(R.string.wz_keys_type_press), (act == null) ? pc.getString(R.string.wz_keys_nothing) :
						pc.getString(ActionFabMediator.labelFor(a, act))));
	}

	// ---------------------------------------------------------------------------------------------
	// Setup: for everyone

	private void tuneBody(LinearLayout body) {
		MainActivityPrefs mp = a.getPrefs();
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		LinearLayout list = card();
		list.addView(switchRow(R.drawable.expand, R.string.wz_tune_bars, R.string.wz_tune_bars_sub,
				() -> mp.getBooleanPref(MainActivityPrefs.SCROLL_HIDE_BARS),
				on -> mp.applyBooleanPref(MainActivityPrefs.SCROLL_HIDE_BARS, on)));
		list.addView(divider());
		list.addView(switchRow(R.drawable.lyrics, R.string.wz_tune_lyrics, R.string.wz_tune_lyrics_sub,
				() -> mp.getBooleanPref(MainActivityPrefs.LYRICS_ON),
				on -> mp.applyBooleanPref(MainActivityPrefs.LYRICS_ON, on)));
		list.addView(divider());
		list.addView(switchRow(R.drawable.clock, R.string.wz_tune_clock, R.string.wz_tune_clock_sub,
				() -> mp.getIntPref(MainActivityPrefs.CLOCK_POS) != MainActivityPrefs.CLOCK_POS_NONE,
				on -> mp.applyIntPref(MainActivityPrefs.CLOCK_POS, on ? MainActivityPrefs.CLOCK_POS_RIGHT :
						MainActivityPrefs.CLOCK_POS_NONE)));
		if (PowerUsageAddon.isEnabled()) {
			list.addView(divider());
			list.addView(switchRow(R.drawable.power_usage, R.string.wz_tune_power, R.string.wz_tune_power_sub,
					() -> mp.getBooleanPref(MainActivityPrefs.INFO_OVERLAY_SHOW_POWER),
					on -> {
						mp.applyBooleanPref(MainActivityPrefs.INFO_OVERLAY_SHOW_POWER, on);
						// The overlay only exists where a position is chosen.
						if (on && (mp.getIntPref(MainActivityPrefs.CLOCK_POS) == MainActivityPrefs.CLOCK_POS_NONE)) {
							mp.applyIntPref(MainActivityPrefs.CLOCK_POS, MainActivityPrefs.CLOCK_POS_RIGHT);
						}
					}));
			list.addView(divider());
			list.addView(switchRow(R.drawable.power_saver, R.string.wz_tune_saver, R.string.wz_tune_saver_sub,
					() -> ps.getIntPref(PowerSaver.AUTO_LEVEL) > 0,
					on -> ps.applyIntPref(PowerSaver.AUTO_LEVEL, on ? 20 : 0)));
		}
		body.addView(list, cardParams(16));

		if (AddonManager.get().getAddon(DataUsageAddon.class) != null) {
			LinearLayout data = card();
			data.addView(text(pc.getString(R.string.wz_tune_data), 15, textPrimary, true));
			TextView ds = text(pc.getString(R.string.wz_tune_data_sub), 13, textSecondary, false);
			ds.setPadding(0, dp(2), 0, dp(10));
			data.addView(ds);
			float[] gb = {0f, 5f, 20f};
			data.addView(chips(new int[]{R.string.wz_data_off, R.string.wz_data_5, R.string.wz_data_20},
					() -> {
						float cur = ps.getFloatPref(DataUsageTracker.LIMIT_GB);
						return (cur <= 0) ? 0 : (cur <= 5) ? 1 : 2;
					},
					i -> {
						ps.applyFloatPref(DataUsageTracker.LIMIT_GB, gb[i]);
						ps.applyFloatPref(DataUsageTracker.WARNING_GB, gb[i] * 0.8f);
					}));
			body.addView(data, cardParams(12));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Pieces

	private LinearLayout card() {
		LinearLayout c = new LinearLayout(pc);
		c.setOrientation(LinearLayout.VERTICAL);
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(22));
		bg.setColor(alpha(cardFill, 0.94f));
		c.setBackground(bg);
		c.setPadding(dp(16), dp(14), dp(16), dp(14));
		return c;
	}

	private LinearLayout.LayoutParams cardParams(int topDp) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(topDp);
		return lp;
	}

	private View divider() {
		View v = new View(pc);
		v.setBackgroundColor(alpha(textSecondary, 0.18f));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, 1);
		lp.setMargins(0, dp(10), 0, dp(10));
		v.setLayoutParams(lp);
		return v;
	}

	/** An icon, a title with a line under it, and a switch that reads and writes through the suppliers. */
	private View switchRow(@DrawableRes int icon, @StringRes int title, @StringRes int sub,
												 BooleanSupplier get, Consumer<Boolean> set) {
		LinearLayout row = new LinearLayout(pc);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		ImageView iv = new ImageView(pc);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(accent));
		iv.setPadding(dp(8), dp(8), dp(8), dp(8));
		GradientDrawable disc = new GradientDrawable();
		disc.setShape(GradientDrawable.OVAL);
		disc.setColor(alpha(accent, 0.16f));
		iv.setBackground(disc);
		row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
		LinearLayout texts = new LinearLayout(pc);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.addView(text(pc.getString(title), 15, textPrimary, true));
		TextView st = text(pc.getString(sub), 12.5f, textSecondary, false);
		st.setPadding(0, dp(1), 0, 0);
		texts.addView(st);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		tlp.setMargins(dp(12), 0, dp(8), 0);
		row.addView(texts, tlp);
		SwitchCompat sw = new SwitchCompat(pc);
		SwitchStyle.apply(sw, pc);
		sw.setChecked(get.getAsBoolean());
		sw.setOnCheckedChangeListener((b, on) -> {
			if (on != get.getAsBoolean()) set.accept(on);
		});
		row.addView(sw, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		row.setOnClickListener(v -> sw.toggle());
		row.setClickable(true);
		return row;
	}

	/** A segmented row of options, the picked one filled with the accent. */
	private View chips(int[] labels, IntSupplier get, IntConsumer pick) {
		LinearLayout row = new LinearLayout(pc);
		row.setOrientation(LinearLayout.HORIZONTAL);
		TextView[] views = new TextView[labels.length];
		Runnable refresh = () -> {
			int sel = get.getAsInt();
			for (int i = 0; i < views.length; i++) {
				boolean on = i == sel;
				views[i].setTextColor(on ? onAccent : textPrimary);
				views[i].setBackground(pressableFilled(on ? accent : chipFill, 18));
			}
		};
		for (int i = 0; i < labels.length; i++) {
			int fi = i;
			TextView t = text(pc.getString(labels[i]), 13, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setEllipsize(android.text.TextUtils.TruncateAt.END);
			t.setPadding(dp(6), dp(10), dp(6), dp(10));
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> {
				pick.accept(fi);
				refresh.run();
			});
			views[i] = t;
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : dp(3), 0, (i == labels.length - 1) ? 0 : dp(3), 0);
			row.addView(t, lp);
		}
		refresh.run();
		return row;
	}

	private TextView button(@DrawableRes int icon, @StringRes int label, Runnable click) {
		TextView t = text(pc.getString(label), 14, textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		t.setCompoundDrawablePadding(dp(8));
		t.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
		t.setPadding(dp(14), 0, dp(14), 0);
		t.setBackground(pressableFilled(chipFill, 24));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> click.run());
		return t;
	}

	/** The slim bar at the top that fills as the pages go by. */
	private final class Progress extends View {
		private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
		private float shown;
		private float target;
		private ValueAnimator anim;

		Progress() {
			super(pc);
		}

		void setFraction(float f) {
			target = f;
			if (anim != null) anim.cancel();
			anim = ValueAnimator.ofFloat(shown, f);
			anim.setDuration(380);
			anim.setInterpolator(new DecelerateInterpolator(1.5f));
			anim.addUpdateListener(v -> {
				shown = (float) v.getAnimatedValue();
				invalidate();
			});
			anim.start();
		}

		@Override
		protected void onDetachedFromWindow() {
			super.onDetachedFromWindow();
			if (anim != null) anim.cancel();
		}

		@Override
		protected void onDraw(android.graphics.Canvas c) {
			float h = getHeight();
			float w = getWidth();
			paint.setColor(alpha(textSecondary, 0.22f));
			c.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, paint);
			paint.setColor(accent);
			c.drawRoundRect(0, 0, Math.max(h, w * shown), h, h / 2f, h / 2f, paint);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Look

	private TextView text(CharSequence s, float sp, int color, boolean bold) {
		TextView t = new TextView(pc);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private Drawable pressable(int fill, float radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		d.setCornerRadius(dp(radiusDp));
		return new RippleDrawable(ColorStateList.valueOf(alpha(accent, 0.25f)), d, null);
	}

	private Drawable pressableFilled(int fill, float radiusDp) {
		return pressable(fill, radiusDp);
	}

	private int color(@AttrRes int attr) {
		TypedValue tv = new TypedValue();
		pc.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private static int alpha(int color, float a) {
		return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24);
	}

	private int dp(float v) {
		return Math.round(v * density);
	}
}
