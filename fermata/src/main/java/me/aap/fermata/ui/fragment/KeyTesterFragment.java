package me.aap.fermata.ui.fragment;

import static android.os.SystemClock.uptimeMillis;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.Key;
import me.aap.fermata.action.KeyTester;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.utils.ui.UiUtils;

/**
 * Settings &gt; Key bindings &gt; Configure Key with Simulator: every key the app knows, laid out as tiles that light up
 * as the matching key (a keyboard, a remote, a car's steering wheel) is pressed, with what it's
 * bound to and a way to change that. While it's open nothing bound runs, see {@link KeyTester}: no
 * play/pause or skip by accident while trying the buttons out. Back has to be pressed twice to
 * leave, since it's a key worth testing too.
 */
public class KeyTesterFragment extends MainActivityFragment implements KeyTester.Listener {
	private static final long DBL_CLICK_INTERVAL = 500;
	private static final long LONG_CLICK_INTERVAL = 1000;
	private static final long BACK_EXIT_INTERVAL = 2500;
	/** D-pad and the like: shown, but left to move the focus around as usual. */
	private static final int[] NAV_KEYS = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
			KeyEvent.KEYCODE_ENTER};

	private final SparseArray<Tile> tiles = new SparseArray<>();
	private final SparseArray<Long> downTimes = new SparseArray<>();
	private final SparseArray<Long> upTimes = new SparseArray<>();
	private final Tile[] transportTiles = new Tile[KeyTester.Transport.values().length];
	private Context palette;
	private int textPrimary;
	private int textSecondary;
	private int tileFill;
	private int accent;
	private int onAccent;
	private float density;
	private int columns;
	@Nullable
	private View heroCard;
	@Nullable
	private TextView heroIcon;
	@Nullable
	private TextView heroTitle;
	@Nullable
	private TextView heroSource;
	@Nullable
	private TextView heroDetail;
	@Nullable
	private TextView heroBindings;
	@Nullable
	private TextView heroChange;
	@Nullable
	private LinearLayout otherSection;
	@Nullable
	private GridLayout otherGrid;
	@Nullable
	private Key heroKey;
	@Nullable
	private ValueAnimator idlePulse;
	private long lastBackUp;
	private boolean listening;
	private boolean resumed;

	@Override
	public int getFragmentId() {
		return R.id.key_tester_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getResources().getString(R.string.key_tester);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		density = ctx.getResources().getDisplayMetrics().density;
		textPrimary = paletteColor(R.attr.musicTextPrimary);
		textSecondary = paletteColor(R.attr.musicTextSecondary);
		tileFill = paletteColor(R.attr.musicChipFill);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);
		columns = Math.max(2, Math.min(6, ctx.getResources().getConfiguration().screenWidthDp / 140));

		Root root = new Root(palette);
		root.setFillViewport(true);
		root.setClipToPadding(false);
		LinearLayout content = new LinearLayout(palette);
		content.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(16);
		content.setPadding(pad, dp(8), pad, pad);
		root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		content.addView(createHero());
		content.addView(createNotice());

		addSection(content, R.string.key_tester_media, new Key[]{Key.MEDIA_PLAY_PAUSE,
				Key.MEDIA_PLAY, Key.MEDIA_PAUSE, Key.MEDIA_STOP, Key.MEDIA_PREVIOUS, Key.MEDIA_NEXT,
				Key.MEDIA_REWIND, Key.MEDIA_FAST_FORWARD, Key.MEDIA_SKIP_BACKWARD, Key.MEDIA_SKIP_FORWARD,
				Key.MEDIA_STEP_BACKWARD, Key.MEDIA_STEP_FORWARD, Key.HEADSETHOOK}, null);
		addSection(content, R.string.key_tester_volume_voice, new Key[]{Key.VOLUME_DOWN,
				Key.VOLUME_UP, Key.VOLUME_MUTE, Key.VOICE_ASSIST, Key.SEARCH}, null);
		addSection(content, R.string.key_tester_navigation, new Key[]{Key.BACK, Key.ESCAPE,
				Key.MENU, Key.DEL}, NAV_KEYS);
		addSection(content, R.string.key_tester_keyboard, new Key[]{Key.M, Key.P, Key.S, Key.X},
				null);

		// Commands that never were a key event: Android Auto's (or a head unit's) play/next/...
		LinearLayout section = section(content, R.string.key_tester_commands);
		TextView hint = text(R.string.key_tester_commands_sub, 13, textSecondary, false);
		hint.setPadding(0, 0, 0, dp(8));
		section.addView(hint);
		GridLayout grid = grid();
		section.addView(grid);
		for (KeyTester.Transport t : KeyTester.Transport.values()) {
			Tile tile = new Tile(grid, transportLabel(t), null, t.key);
			transportTiles[t.ordinal()] = tile;
		}

		// Anything else pressed shows up here as it comes.
		otherSection = section(content, R.string.key_tester_other);
		otherGrid = grid();
		otherSection.addView(otherGrid);
		otherSection.setVisibility(View.GONE);
		return root;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		// The list scrolls under the translucent tool bar and nav bar: reserve room for them.
		getActivityDelegate().insetScrollableContent((ViewGroup) view);
		refreshBindings();
		showIdle();
	}

	@Override
	public void onResume() {
		super.onResume();
		resumed = true;
		updateListening();
	}

	@Override
	public void onPause() {
		super.onPause();
		resumed = false;
		updateListening();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden) refreshBindings(); // May have been changed in Settings in the meantime.
		updateListening();
	}

	@Override
	public void onDestroyView() {
		setListening(false);
		if (idlePulse != null) idlePulse.cancel();
		idlePulse = null;
		tiles.clear();
		heroCard = null;
		heroIcon = heroTitle = heroSource = heroDetail = heroBindings = heroChange = null;
		otherSection = null;
		otherGrid = null;
		super.onDestroyView();
	}

	@Override
	public boolean onBackPressed() {
		// Back to the page it was opened from, not to whichever tab is active.
		getActivityDelegate().showFragment(R.id.settings_fragment, SettingsFragment.SHOW_KEY_BINDINGS);
		return true;
	}

	private void updateListening() {
		setListening(resumed && !isHidden() && (getView() != null));
	}

	private void setListening(boolean on) {
		if (listening == on) return;
		listening = on;
		KeyTester.setListener(on ? this : null);
	}

	@Override
	public boolean onKeyEvent(KeyEvent e, boolean session) {
		int code = e.getKeyCode();
		Key k = Key.get(code);
		boolean consume = (k != null) || isMediaKey(code) || (code == KeyEvent.KEYCODE_BACK);
		long now = uptimeMillis();

		switch (e.getAction()) {
			case KeyEvent.ACTION_DOWN -> {
				if (e.getRepeatCount() == 0) {
					downTimes.put(code, now);
					press(code, k, session, getString(R.string.key_tester_pressed));
				} else {
					Long down = downTimes.get(code);
					if ((down != null) && ((now - down) >= LONG_CLICK_INTERVAL)) {
						showPressType(getString(R.string.key_tester_holding));
					}
				}
			}
			case KeyEvent.ACTION_UP -> {
				Long down = downTimes.get(code);
				Long prevUp = upTimes.get(code);
				upTimes.put(code, now);
				downTimes.remove(code);
				String type;
				if ((down != null) && ((now - down) >= LONG_CLICK_INTERVAL)) {
					type = getString(R.string.key_tester_long_click,
							String.format(java.util.Locale.ROOT, "%.1f", (now - down) / 1000f));
				} else if ((prevUp != null) && ((now - prevUp) <= DBL_CLICK_INTERVAL)) {
					type = getString(R.string.key_tester_dbl_click);
				} else {
					type = getString(R.string.key_tester_click);
				}
				showPressType(type);
				release(code);

				if (code == KeyEvent.KEYCODE_BACK) {
					if ((now - lastBackUp) <= BACK_EXIT_INTERVAL) {
						lastBackUp = 0;
						getActivityDelegate().post(this::onBackPressed);
					} else {
						lastBackUp = now;
						UiUtils.showToast(getActivityDelegate().getContext(), R.string.key_tester_back_again);
					}
				}
			}
			case KeyEvent.ACTION_MULTIPLE -> {
				press(code, k, session, getString(R.string.key_tester_dbl_click));
				release(code);
			}
		}
		return consume;
	}

	@Override
	public boolean onTransport(KeyTester.Transport t) {
		Tile tile = transportTiles[t.ordinal()];
		if (tile != null) {
			tile.flash();
			boolean bound = Key.getPrefs().getBooleanPref(Key.BIND_TRANSPORT);
			showHero(transportLabel(t), getString(R.string.key_tester_src_command),
					getString(bound ? R.string.key_tester_command_bound : R.string.key_tester_command_unbound,
							keyLabel(t.key)), t.key);
		}
		return true; // Never let it play/skip while testing.
	}

	private void press(int code, @Nullable Key k, boolean session, String type) {
		Tile tile = tiles.get(code);
		if (tile == null) tile = addOtherTile(code);
		if (tile != null) tile.press();
		String detail = KeyEvent.keyCodeToString(code) + " · " + code + " · " + type;
		showHero(keyLabel(code), getString(session ? R.string.key_tester_src_media_button :
				R.string.key_tester_src_window), detail, k);
	}

	private void release(int code) {
		Tile tile = tiles.get(code);
		if (tile != null) tile.release();
	}

	private void showPressType(String type) {
		if (heroDetail == null) return;
		CharSequence d = heroDetail.getText();
		String s = (d == null) ? "" : d.toString();
		int i = s.lastIndexOf(" · ");
		heroDetail.setText(((i < 0) ? s : s.substring(0, i)) + " · " + type);
	}

	private static boolean isMediaKey(int code) {
		return KeyEvent.keyCodeToString(code).startsWith("KEYCODE_MEDIA_") ||
				(code == KeyEvent.KEYCODE_HEADSETHOOK) || (code == KeyEvent.KEYCODE_VOLUME_UP) ||
				(code == KeyEvent.KEYCODE_VOLUME_DOWN) || (code == KeyEvent.KEYCODE_VOLUME_MUTE);
	}

	// ---- Hero card: the last key pressed ----

	private View createHero() {
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setGravity(Gravity.CENTER_HORIZONTAL);
		int pad = dp(20);
		card.setPadding(pad, pad, pad, pad);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ColorUtils.setAlphaComponent(accent, 0x1F));
		bg.setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x66));
		bg.setCornerRadius(dp(24));
		card.setBackground(bg);

		heroIcon = new TextView(palette);
		heroIcon.setText("⌨");
		heroIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
		heroIcon.setTextColor(accent);
		heroIcon.setGravity(Gravity.CENTER);
		card.addView(heroIcon);

		heroTitle = text(R.string.key_tester_idle, 24, textPrimary, true);
		heroTitle.setGravity(Gravity.CENTER);
		card.addView(heroTitle);

		heroSource = new TextView(palette);
		heroSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		heroSource.setTextColor(onAccent);
		heroSource.setTypeface(Typeface.DEFAULT_BOLD);
		heroSource.setPadding(dp(10), dp(3), dp(10), dp(3));
		heroSource.setBackground(pill(accent));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(6);
		card.addView(heroSource, lp);

		heroDetail = text(R.string.key_tester_idle_sub, 13, textSecondary, false);
		heroDetail.setGravity(Gravity.CENTER);
		heroDetail.setPadding(0, dp(8), 0, 0);
		card.addView(heroDetail);

		heroBindings = text(0, 14, textPrimary, false);
		heroBindings.setGravity(Gravity.CENTER);
		heroBindings.setPadding(0, dp(8), 0, 0);
		card.addView(heroBindings);

		heroChange = text(R.string.key_tester_change, 15, onAccent, true);
		heroChange.setGravity(Gravity.CENTER);
		heroChange.setPadding(dp(24), dp(10), dp(24), dp(10));
		heroChange.setBackground(pill(accent));
		heroChange.setFocusable(true);
		heroChange.setClickable(true);
		heroChange.setOnClickListener(v -> {
			if (heroKey != null) openBinding(heroKey);
		});
		lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(12);
		card.addView(heroChange, lp);

		heroCard = card;
		return card;
	}

	private void showIdle() {
		if ((heroSource == null) || (heroBindings == null) || (heroChange == null)) return;
		heroSource.setVisibility(View.GONE);
		heroBindings.setVisibility(View.GONE);
		heroChange.setVisibility(View.GONE);
		// A slow breathing icon until the first key comes in.
		if ((idlePulse == null) && (heroIcon != null)) {
			TextView icon = heroIcon;
			idlePulse = ValueAnimator.ofFloat(1f, 1.15f);
			idlePulse.setDuration(900);
			idlePulse.setRepeatMode(ValueAnimator.REVERSE);
			idlePulse.setRepeatCount(ValueAnimator.INFINITE);
			idlePulse.addUpdateListener(a -> {
				float s = (float) a.getAnimatedValue();
				icon.setScaleX(s);
				icon.setScaleY(s);
			});
			idlePulse.start();
		}
	}

	private void showHero(String title, String source, String detail, @Nullable Key k) {
		if ((heroTitle == null) || (heroSource == null) || (heroDetail == null) ||
				(heroBindings == null) || (heroChange == null) || (heroCard == null)) {
			return;
		}
		if (idlePulse != null) {
			idlePulse.cancel();
			idlePulse = null;
			if (heroIcon != null) heroIcon.setVisibility(View.GONE);
		}
		heroKey = k;
		heroTitle.setText(title);
		heroSource.setText(source);
		heroSource.setVisibility(View.VISIBLE);
		heroDetail.setText(detail);
		if (k != null) {
			heroBindings.setText(bindingsText(k));
			heroBindings.setVisibility(View.VISIBLE);
			heroChange.setVisibility(View.VISIBLE);
		} else {
			heroBindings.setText(R.string.key_tester_not_bindable);
			heroBindings.setVisibility(View.VISIBLE);
			heroChange.setVisibility(View.GONE);
		}
		// A little bump, so a repeated press of the same key still shows it was seen.
		heroTitle.setAlpha(0.3f);
		heroTitle.setTranslationY(dp(8));
		heroTitle.animate().alpha(1f).translationY(0).setDuration(220)
				.setInterpolator(new DecelerateInterpolator()).start();
		heroCard.setScaleX(0.97f);
		heroCard.setScaleY(0.97f);
		heroCard.animate().scaleX(1f).scaleY(1f).setDuration(320)
				.setInterpolator(new OvershootInterpolator(3f)).start();
	}

	private String bindingsText(Key k) {
		return getString(R.string.key_tester_click) + ": " + actionName(k.getClickAction()) + "   ·   " +
				getString(R.string.key_tester_dbl_click) + ": " + actionName(k.getDblClickAction()) +
				"   ·   " + getString(R.string.key_tester_long) + ": " +
				actionName(k.getLongClickAction());
	}

	private String actionName(@Nullable Action a) {
		return getString((a == null) ? R.string.action_none : a.getName());
	}

	private void openBinding(Key k) {
		getActivityDelegate().showFragment(R.id.settings_fragment, SettingsFragment.keySettings(k));
	}

	// ---- Notice and sections ----

	private View createNotice() {
		TextView t = text(R.string.key_tester_notice, 13, textSecondary, false);
		t.setPadding(dp(14), dp(10), dp(14), dp(10));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(tileFill);
		bg.setCornerRadius(dp(14));
		t.setBackground(bg);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(12);
		t.setLayoutParams(lp);
		return t;
	}

	private void addSection(LinearLayout content, @StringRes int title, Key[] keys,
													@Nullable int[] navKeys) {
		LinearLayout section = section(content, title);
		GridLayout grid = grid();
		section.addView(grid);
		for (Key k : keys) tiles.put(k.getCode(), new Tile(grid, keyLabel(k), k, k));
		if (navKeys != null) {
			for (int code : navKeys) tiles.put(code, new Tile(grid, keyLabel(code), null, null));
		}
	}

	private LinearLayout section(LinearLayout content, @StringRes int title) {
		LinearLayout section = new LinearLayout(palette);
		section.setOrientation(LinearLayout.VERTICAL);
		section.setPadding(0, dp(20), 0, 0);
		TextView t = text(title, 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		t.setPadding(dp(4), 0, 0, dp(8));
		section.addView(t);
		content.addView(section);
		return section;
	}

	private GridLayout grid() {
		GridLayout g = new GridLayout(palette);
		g.setColumnCount(columns);
		g.setUseDefaultMargins(false);
		return g;
	}

	@Nullable
	private Tile addOtherTile(int code) {
		if ((otherGrid == null) || (otherSection == null)) return null;
		otherSection.setVisibility(View.VISIBLE);
		Tile t = new Tile(otherGrid, keyLabel(code), null, null);
		tiles.put(code, t);
		return t;
	}

	private void refreshBindings() {
		for (int i = 0, n = tiles.size(); i < n; i++) tiles.valueAt(i).refresh();
		for (Tile t : transportTiles) if (t != null) t.refresh();
		if ((heroKey != null) && (heroBindings != null) && (heroBindings.getVisibility() == View.VISIBLE)) {
			heroBindings.setText(bindingsText(heroKey));
		}
	}

	// ---- Labels ----

	private String keyLabel(Key k) {
		return keyLabel(k.getCode());
	}

	private String keyLabel(int code) {
		@StringRes int res = switch (code) {
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> R.string.key_label_play_pause;
			case KeyEvent.KEYCODE_MEDIA_PLAY -> R.string.action_play;
			case KeyEvent.KEYCODE_MEDIA_PAUSE -> R.string.action_pause;
			case KeyEvent.KEYCODE_MEDIA_STOP -> R.string.action_stop;
			case KeyEvent.KEYCODE_MEDIA_PREVIOUS -> R.string.key_label_previous;
			case KeyEvent.KEYCODE_MEDIA_NEXT -> R.string.key_label_next;
			case KeyEvent.KEYCODE_MEDIA_REWIND -> R.string.key_label_rewind;
			case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> R.string.key_label_fast_forward;
			case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> R.string.key_label_skip_back;
			case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> R.string.key_label_skip_forward;
			case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> R.string.key_label_step_back;
			case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> R.string.key_label_step_forward;
			case KeyEvent.KEYCODE_HEADSETHOOK -> R.string.key_label_headset;
			case KeyEvent.KEYCODE_VOLUME_UP -> R.string.key_label_volume_up;
			case KeyEvent.KEYCODE_VOLUME_DOWN -> R.string.key_label_volume_down;
			case KeyEvent.KEYCODE_VOLUME_MUTE -> R.string.key_label_mute;
			case KeyEvent.KEYCODE_VOICE_ASSIST -> R.string.key_label_voice;
			case KeyEvent.KEYCODE_SEARCH -> R.string.key_label_search;
			case KeyEvent.KEYCODE_BACK -> R.string.key_label_back;
			case KeyEvent.KEYCODE_ESCAPE -> R.string.key_label_escape;
			case KeyEvent.KEYCODE_MENU -> R.string.key_label_menu;
			case KeyEvent.KEYCODE_DEL -> R.string.key_label_delete;
			case KeyEvent.KEYCODE_DPAD_UP -> R.string.key_label_up;
			case KeyEvent.KEYCODE_DPAD_DOWN -> R.string.key_label_down;
			case KeyEvent.KEYCODE_DPAD_LEFT -> R.string.key_label_left;
			case KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.key_label_right;
			case KeyEvent.KEYCODE_DPAD_CENTER -> R.string.key_label_center;
			case KeyEvent.KEYCODE_ENTER -> R.string.key_label_enter;
			default -> 0;
		};
		if (res != 0) return getString(res);
		// KEYCODE_SOME_KEY -> Some key
		String s = KeyEvent.keyCodeToString(code);
		if (s.startsWith("KEYCODE_")) s = s.substring(8);
		s = s.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
		return s.isEmpty() ? String.valueOf(code) :
				Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private String transportLabel(KeyTester.Transport t) {
		return getString(switch (t) {
			case PLAY -> R.string.action_play;
			case PAUSE -> R.string.action_pause;
			case STOP -> R.string.action_stop;
			case NEXT -> R.string.key_label_next;
			case PREV -> R.string.key_label_previous;
			case FF -> R.string.key_label_fast_forward;
			case RW -> R.string.key_label_rewind;
		});
	}

	// ---- Helpers ----

	private TextView text(@StringRes int res, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		if (res != 0) t.setText(res);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private GradientDrawable pill(int color) {
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(dp(100));
		return g;
	}

	private int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(int v) {
		return Math.round(v * density);
	}

	/** One key: lights up in the accent color and grows a little while it's held. */
	private final class Tile {
		final LinearLayout view;
		final TextView label;
		final TextView binding;
		@Nullable
		final Key bindingOf;
		final GradientDrawable bg;
		@Nullable
		ValueAnimator colorAnim;
		float glow; // 0: idle, 1: lit

		Tile(GridLayout grid, String name, @Nullable Key bindingOf, @Nullable Key opens) {
			this.bindingOf = bindingOf;
			view = new LinearLayout(palette);
			view.setOrientation(LinearLayout.VERTICAL);
			view.setGravity(Gravity.CENTER);
			view.setMinimumHeight(dp(72));
			view.setPadding(dp(8), dp(10), dp(8), dp(10));
			bg = new GradientDrawable();
			bg.setCornerRadius(dp(16));
			bg.setColor(tileFill);
			view.setBackground(bg);
			label = text(0, 15, textPrimary, true);
			label.setText(name);
			label.setGravity(Gravity.CENTER);
			label.setMaxLines(2);
			view.addView(label);
			binding = text(0, 12, textSecondary, false);
			binding.setGravity(Gravity.CENTER);
			binding.setMaxLines(1);
			binding.setEllipsize(android.text.TextUtils.TruncateAt.END);
			view.addView(binding);

			view.setFocusable(true);
			view.setClickable(true);
			view.setOnClickListener(v -> {
				if (opens != null) openBinding(opens);
				else UiUtils.showToast(v.getContext(), R.string.key_tester_not_bindable);
			});
			// A focused tile (remote / rotary) is outlined, so it's clear where Enter goes.
			view.setOnFocusChangeListener((v, f) -> bg.setStroke(f ? dp(2) : 0, accent));

			GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
					GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
			lp.width = 0;
			int m = dp(4);
			lp.setMargins(m, m, m, m);
			grid.addView(view, lp);
			refresh();
		}

		void refresh() {
			if (bindingOf == null) {
				binding.setVisibility(View.GONE);
				return;
			}
			binding.setVisibility(View.VISIBLE);
			binding.setText(actionName(bindingOf.getClickAction()));
		}

		void press() {
			animateTo(1f, 160);
			view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180)
					.setInterpolator(new OvershootInterpolator(2.5f)).start();
		}

		void release() {
			animateTo(0f, 700);
			view.animate().scaleX(1f).scaleY(1f).setDuration(300)
					.setInterpolator(new DecelerateInterpolator()).start();
		}

		/** A command has no key up: lit, then let go of shortly after. */
		void flash() {
			press();
			view.postDelayed(this::release, 250);
		}

		private void animateTo(float target, long duration) {
			if (colorAnim != null) colorAnim.cancel();
			ArgbEvaluator argb = new ArgbEvaluator();
			colorAnim = ValueAnimator.ofFloat(glow, target);
			colorAnim.setDuration(duration);
			colorAnim.addUpdateListener(a -> {
				glow = (float) a.getAnimatedValue();
				bg.setColor((int) argb.evaluate(glow, tileFill, accent));
				int fg = (int) argb.evaluate(glow, textPrimary, onAccent);
				label.setTextColor(fg);
				binding.setTextColor((int) argb.evaluate(glow, textSecondary, onAccent));
			});
			colorAnim.start();
		}
	}

	/**
	 * Sees the keys before any focused child does, so a bound key never reaches a button here
	 * either (and is reported once, see {@link KeyTester#onKeyEvent}).
	 */
	private static final class Root extends ScrollView {
		Root(Context ctx) {
			super(ctx);
		}

		@Override
		public boolean dispatchKeyEvent(KeyEvent e) {
			if (KeyTester.onKeyEvent(e, false)) return true;
			return super.dispatchKeyEvent(e);
		}
	}
}
