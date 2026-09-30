package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.ui.view.BehindBarsLayers;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The YouTube suggestions tab: the first few videos of the user's YouTube feed (how many is a
 * YouTube addon setting) floating around as big thumbnail bubbles, see {@link YoutubeBubbleField}.
 * Tapping one plays it: in the YouTube tab, or -- while listening as music -- in the Music tab,
 * as the "Tapping a bubble" setting and the Video/Music switch at the top say. A long press offers
 * both. The background runs on behind the floating bars like the Music tab's.
 */
@Keep
public class YoutubeBubblesFragment extends MainActivityFragment
		implements PreferenceStore.Listener, YoutubeBubbleField.Listener {
	/** The feed is kept for a while, so coming back to the tab doesn't reload it every time. */
	private static final long CACHE_MS = 10 * 60 * 1000L;
	private static List<YoutubeFeed.Video> cache = new ArrayList<>();
	private static long cacheTime;
	/** How many videos the cached feed was asked for: more than that isn't there to show. */
	private static int cacheWanted;

	private YoutubeBubbleField field;
	private TextView status;
	private TextView videoPill;
	private TextView musicPill;
	private BehindBarsLayers layers;
	@Nullable
	private YoutubeFeed feed;
	private boolean loading;
	private boolean light;
	private final int[] ins = new int[2];
	private final ViewTreeObserver.OnPreDrawListener insetSync = () -> {
		syncInsets();
		return true;
	};

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.youtube_bubbles_fragment;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return dynCtx(requireContext()).getString(R.string.yt_bubbles_title);
	}

	/** The background runs on behind a side nav bar's pill, like the Music tab's. */
	@Override
	public boolean drawsBehindSideNavBar() {
		return true;
	}

	@Nullable
	private YoutubeAddon addon() {
		return AddonManager.get().getAddon(YoutubeAddon.class);
	}

	private int dp(int v) {
		return toIntPx(requireContext(), v);
	}

	// ---------------------------------------------------------------- view

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = dynCtx(inflater.getContext());
		MainActivityDelegate a = getActivityDelegate();
		light = MusicPlayerFragment.isLightTheme(ctx);
		boolean car = a.isCarActivity();

		FrameLayout root = new FrameLayout(ctx);
		root.setClipChildren(false);
		root.setClipToPadding(false);

		// The layers reaching under the bars, see BehindBarsLayers.
		View backdrop = new View(ctx);
		backdrop.setBackgroundColor(light ? 0xFFF3F4F7 : 0xFF101014);
		View glow = new View(ctx);
		glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
				light ? new int[]{0xFFFBE4EC, 0xFFE4ECFB, 0xFFE0F5EE} :
						new int[]{0xFF2A1040, 0xFF0E1B3A, 0xFF0B3A3A}));
		root.addView(backdrop, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		root.addView(glow, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		field = new YoutubeBubbleField(ctx, car);
		root.addView(field, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		status = new TextView(ctx);
		status.setTextColor(light ? 0x99000000 : 0xB3FFFFFF);
		status.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 22 : 17);
		status.setGravity(Gravity.CENTER);
		status.setPadding(dp(32), 0, dp(32), 0);
		root.addView(status, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER));

		// Shuffle and the Video/Music switch, in a pill under the tool bar.
		LinearLayout controls = new LinearLayout(ctx);
		controls.setOrientation(LinearLayout.HORIZONTAL);
		controls.setGravity(Gravity.CENTER_VERTICAL);
		controls.setPadding(dp(4), dp(4), dp(4), dp(4));
		GradientDrawable cbg = new GradientDrawable();
		cbg.setColor(light ? 0xE6FFFFFF : 0xB31C1C22);
		cbg.setCornerRadius(dp(40));
		controls.setBackground(cbg);
		controls.setElevation(dp(6));
		TextView shuffle = pill(ctx, dynCtx(ctx).getString(R.string.yt_bubbles_refresh), false, car);
		shuffle.setOnClickListener(v -> onShuffle());
		videoPill = pill(ctx, dynCtx(ctx).getString(R.string.yt_bubbles_mode_video), false, car);
		videoPill.setOnClickListener(v -> setMode(YoutubeAddon.BUBBLES_TAP_VIDEO));
		musicPill = pill(ctx, dynCtx(ctx).getString(R.string.yt_bubbles_mode_music), false, car);
		musicPill.setOnClickListener(v -> setMode(YoutubeAddon.BUBBLES_TAP_MUSIC));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(car ? 48 : 40));
		plp.setMarginEnd(dp(4));
		controls.addView(shuffle, plp);
		controls.addView(videoPill, plp);
		controls.addView(musicPill, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(car ? 48 : 40)));
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
				Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		root.addView(controls, clp);
		// Right under the tool bar's pill, like a web page's top.
		a.insetWebViewTop(controls);

		layers = new BehindBarsLayers(a, root, backdrop, glow);
		return root;
	}

	private TextView pill(Context ctx, String text, boolean selected, boolean car) {
		TextView t = new TextView(ctx);
		t.setText(text);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 17 : 14);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setPadding(dp(18), 0, dp(18), 0);
		t.setClickable(true);
		t.setFocusable(true);
		stylePill(t, selected);
		return t;
	}

	private void stylePill(TextView t, boolean selected) {
		int fill = selected ? (light ? 0xFF1C1C1E : 0xFFFFFFFF) : 0x00000000;
		t.setTextColor(selected ? (light ? 0xFFFFFFFF : 0xFF000000) : (light ? 0xDE000000 : 0xFFFFFFFF));
		GradientDrawable content = new GradientDrawable();
		content.setColor(fill);
		content.setCornerRadius(dp(40));
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(Color.BLACK);
		mask.setCornerRadius(dp(40));
		Drawable d = new RippleDrawable(ColorStateList.valueOf(light ? 0x29000000 : 0x40FFFFFF),
				content, mask);
		t.setBackground(d);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		field.setListener(this);
		view.getViewTreeObserver().addOnPreDrawListener(insetSync);
		layers.attach();
		YoutubeAddon addon = addon();
		if (addon != null) addon.getPreferenceStore().addBroadcastListener(this);
		applySpeed();
		updateModePills();
		updateRunning();
		if (!isHidden()) maybeLoad();
	}

	@Override
	public void onDestroyView() {
		YoutubeAddon addon = addon();
		if (addon != null) addon.getPreferenceStore().removeBroadcastListener(this);
		View v = getView();
		if (v != null) v.getViewTreeObserver().removeOnPreDrawListener(insetSync);
		if (layers != null) layers.detach();
		if (field != null) field.setRunning(false);
		if (feed != null) feed.cancel();
		feed = null;
		loading = false;
		super.onDestroyView();
	}

	/** Keeps the bubbles clear of the tool bar, the switch pill under it and the bottom bars. */
	private void syncInsets() {
		MainActivityDelegate a = getActivityDelegate();
		if ((field == null) || !a.computeContentInsets(field, ins)) return;
		field.setInsets(ins[0] + dp(56), ins[1]);
	}

	@Override
	public void onResume() {
		super.onResume();
		updateRunning();
	}

	@Override
	public void onPause() {
		super.onPause();
		updateRunning();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		updateRunning();
		if (!hidden) {
			updateModePills();
			maybeLoad();
		}
	}

	/** The bubbles only move while the tab is actually on screen. */
	private void updateRunning() {
		if (field != null) field.setRunning(isResumed() && !isHidden());
	}

	// ---------------------------------------------------------------- feed

	private int count(YoutubeAddon addon) {
		return Math.max(1, addon.getPreferenceStore().getIntPref(YoutubeAddon.BUBBLES_COUNT));
	}

	private void applySpeed() {
		YoutubeAddon addon = addon();
		if ((addon != null) && (field != null)) {
			field.setSpeed(addon.getPreferenceStore().getIntPref(YoutubeAddon.BUBBLES_SPEED) / 100f);
		}
	}

	/** Loads the feed unless what's kept is still fresh. */
	private void maybeLoad() {
		YoutubeAddon addon = addon();
		if (addon == null) {
			showStatus(R.string.yt_bubbles_empty);
			return;
		}
		boolean fresh = !cache.isEmpty() && (SystemClock.elapsedRealtime() - cacheTime < CACHE_MS) &&
				(count(addon) <= cacheWanted);
		if (fresh) {
			if (field.isEmpty()) show();
		} else {
			loadFeed();
		}
	}

	private void loadFeed() {
		YoutubeAddon addon = addon();
		if ((addon == null) || loading) return;
		int wanted = Math.min(40, Math.max(count(addon), 24));
		loading = true;
		if (field.isEmpty()) showStatus(R.string.yt_bubbles_loading);
		if (feed != null) feed.cancel();
		feed = new YoutubeFeed(requireContext());
		feed.start(wanted, list -> {
			loading = false;
			if (getView() == null) return;
			if (!list.isEmpty()) {
				cache = list;
				cacheTime = SystemClock.elapsedRealtime();
				cacheWanted = wanted;
			}
			show();
		});
	}

	/** Shows the first few of the kept feed, as many as the setting says. */
	private void show() {
		YoutubeAddon addon = addon();
		if ((addon == null) || (field == null)) return;
		if (cache.isEmpty()) {
			field.setVideos(Collections.emptyList());
			showStatus(R.string.yt_bubbles_empty);
			return;
		}
		int n = Math.min(count(addon), cache.size());
		field.setVideos(new ArrayList<>(cache.subList(0, n)));
		status.setVisibility(View.GONE);
	}

	private void showStatus(int text) {
		if (status == null) return;
		status.setText(dynCtx(requireContext()).getString(text));
		status.setVisibility(View.VISIBLE);
	}

	private void onShuffle() {
		if (!field.isEmpty()) field.shuffle();
		loadFeed();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (getView() == null) return;
		if (prefs.contains(YoutubeAddon.BUBBLES_SPEED)) applySpeed();
		if (prefs.contains(YoutubeAddon.BUBBLES_TAP)) updateModePills();
		if (prefs.contains(YoutubeAddon.BUBBLES_COUNT) && !isHidden()) maybeLoad();
		if (prefs.contains(YoutubeAddon.BUBBLES_COUNT) && isHidden()) {
			// Shown afresh, with the new number, the next time the tab is opened.
			field.setVideos(Collections.emptyList());
		}
	}

	// ---------------------------------------------------------------- playing

	/** What tapping a bubble does now: the setting, or -- on Auto -- whatever mode is on. */
	private int effectiveMode() {
		YoutubeAddon addon = addon();
		if ((addon == null) || !MusicPlayer.isEnabled()) return YoutubeAddon.BUBBLES_TAP_VIDEO;
		int m = addon.getPreferenceStore().getIntPref(YoutubeAddon.BUBBLES_TAP);
		if (m == YoutubeAddon.BUBBLES_TAP_AUTO) {
			return MusicPlayer.isMusicModeActive(getActivityDelegate()) ? YoutubeAddon.BUBBLES_TAP_MUSIC :
					YoutubeAddon.BUBBLES_TAP_VIDEO;
		}
		return m;
	}

	private void setMode(int mode) {
		YoutubeAddon addon = addon();
		if (addon != null) addon.getPreferenceStore().applyIntPref(YoutubeAddon.BUBBLES_TAP, mode);
		updateModePills();
	}

	private void updateModePills() {
		if (videoPill == null) return;
		int m = effectiveMode();
		stylePill(videoPill, m != YoutubeAddon.BUBBLES_TAP_MUSIC);
		stylePill(musicPill, m == YoutubeAddon.BUBBLES_TAP_MUSIC);
		musicPill.setVisibility(MusicPlayer.isEnabled() ? View.VISIBLE : View.GONE);
	}

	@Override
	public void onBubbleClick(YoutubeFeed.Video video) {
		play(video, effectiveMode());
	}

	@Override
	public void onBubbleLongClick(YoutubeFeed.Video video) {
		Context ctx = dynCtx(requireContext());
		getActivityDelegate().getContextMenu().show(b -> {
			b.setTitle(video.title);
			b.addItem(R.id.yt_bubble_play_video, me.aap.fermata.R.drawable.video,
					ctx.getString(R.string.yt_bubbles_mode_video)).setHandler(i -> {
				play(video, YoutubeAddon.BUBBLES_TAP_VIDEO);
				return true;
			});
			if (MusicPlayer.isEnabled()) {
				b.addItem(R.id.yt_bubble_play_music, me.aap.fermata.R.drawable.music,
						ctx.getString(R.string.yt_bubbles_mode_music)).setHandler(i -> {
					play(video, YoutubeAddon.BUBBLES_TAP_MUSIC);
					return true;
				});
			}
		});
	}

	/** Plays {@code video} in the YouTube tab, or as music in the Music tab. */
	private void play(YoutubeFeed.Video video, int mode) {
		MainActivityDelegate a = getActivityDelegate();
		YoutubeAddon addon = addon();
		if (addon == null) return;
		addon.cacheVideoTitle(video.id, video.title);

		if ((mode == YoutubeAddon.BUBBLES_TAP_MUSIC) && MusicPlayer.isEnabled() &&
				(a.getLib() instanceof DefaultMediaLib lib)) {
			YoutubeVideoItem item = new YoutubeVideoItem(video.id, addon.getRootItem(lib));
			// Replaces the music queue with it and opens the Music tab.
			MusicPlayer.play(a, Collections.singletonList(item), 0);
			return;
		}

		int id = me.aap.fermata.R.id.youtube_fragment;
		ActivityFragment f = a.getFragment(id);
		// Never opened yet: its page is created (and laid out, YouTube won't play in a zero-size
		// window) without showing the tab.
		if (f == null) f = a.preloadFragment(id);
		if (f instanceof YoutubeFragment yf) {
			yf.playVideoNow(video.id, video.title);
			a.showFragment(id);
		}
	}
}
