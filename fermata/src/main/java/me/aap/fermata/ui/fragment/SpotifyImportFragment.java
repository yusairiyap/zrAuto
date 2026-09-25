package me.aap.fermata.ui.fragment;

import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.spotify.SpotifyApi;
import me.aap.fermata.spotify.SpotifyAuth;
import me.aap.fermata.spotify.SpotifyClient;
import me.aap.fermata.spotify.SpotifyImportEngine;
import me.aap.fermata.spotify.SpotifyImportModel.Playlist;
import me.aap.fermata.spotify.SpotifyImportStore;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.SpotifyPlaylistWriter;
import me.aap.fermata.spotify.SpotifyPrefs;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.Utils;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * Imports Spotify playlists as local playlists of matching YouTube videos.
 * <p>
 * Playlists come from the signed-in user's account (an in-page picker of their playlists, see
 * {@link SpotifyApi}) or from pasted links. Each playlist is a card that can be opened to pick
 * individual tracks -- everything is selected by default -- and renamed before engine.isImporting(). Tracks
 * are matched against YouTube progressively in the background, one search at a time (the open
 * playlist first), so each shows the video it would import as; "Search more" lists alternatives,
 * and any video can be previewed.
 * <p>
 * Import first resolves every remaining match (with progress, cancellable), and only then writes
 * the playlists in one quick local step -- so cancelling never leaves a half-imported playlist
 * behind. See {@link SpotifyClient} and {@link YoutubeSearch} for why no YouTube API key is needed.
 * <p>
 * Like the other tabs, list or grid follows the tool bar's list/grid toggle, and grid cards use
 * the same full-bleed image and gradient style as the media tabs.
 */
public class SpotifyImportFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_PLAYLIST = 1;
	private static final int TYPE_TRACK = 2;
	private static final int TYPE_ALT = 3;
	private static final int TYPE_ALT_STATUS = 4;
	private static final int TYPE_EMPTY = 5;
	/** A playlist of the "My Spotify playlists" picker. */
	private static final int TYPE_PICK = 6;
	/** Added to card types in grid mode, so list and grid holders never mix. */
	private static final int GRID = 100;
	private static final int MAX_RESULTS = 10;
	/** Pause between consecutive YouTube searches, to stay well clear of any rate limiting. */
	private static final long SEARCH_DELAY_MS = 400;
	private static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";
	/** How the dashboard address appears in {@code R.string.spotify_setup_steps}. */
	private static final String DASHBOARD_LABEL = "developer.spotify.com/dashboard";
	private static final String WEB_BROWSER_ADDON_CLASS = "me.aap.fermata.addon.web.WebBrowserAddon";

	private final Handler handler = new Handler(Looper.getMainLooper());
	/** The import's state and background work, which outlive this screen. */
	private final SpotifyImportEngine engine = SpotifyImportEngine.get();
	/** The engine's live list (main thread only). */
	private final List<Playlist> playlists = engine.getPlaylists();
	private final List<Row> rows = new ArrayList<>();
	/** Decoded thumbnails, so rebinding a row doesn't flash the placeholder. */
	private final LruCache<String, Bitmap> images = new LruCache<>(120);
	/** Lists the user's Spotify playlists for the picker. */
	private final ExecutorService pickerExecutor = Executors.newSingleThreadExecutor();
	private final SpotifyImportEngine.Listener engineListener = new SpotifyImportEngine.Listener() {
		@Override
		public void onTrackChanged(Track t) {
			refreshTrack(t);
			refreshPlaylistOf(t);
			refreshHeader();
		}

		@Override
		public void onChanged() {
			if ((current != null) && !playlists.contains(current)) {
				current = null;
				getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
			}
			rebuild();
		}

		@Override
		public void onImportFinished(SpotifyImportEngine.ImportResult result) {
			onImportDone(result);
		}
	};
	private boolean destroyed;
	@Nullable
	private Playlist current;
	/** Non-null while the "My Spotify playlists" picker is shown. */
	@Nullable
	private List<SpotifyApi.PlaylistInfo> picker;
	private final Set<String> pickerSelected = new HashSet<>();
	private Adapter adapter;
	@Nullable
	private RecyclerView list;
	private boolean grid;

	/**
	 * Opens the import screen, asking for Spotify links straight away if nothing has been loaded
	 * yet. Used by Settings and by the Playlists tab's menus.
	 */
	public static void open(MainActivityDelegate a) {
		ActivityFragment f = a.showFragment(R.id.spotify_import_fragment);
		if ((f instanceof SpotifyImportFragment sf) && sf.playlists.isEmpty()) {
			sf.handler.post(sf::addPlaylists);
		}
	}

	/**
	 * Opens the Spotify login page in the browser. Spotify redirects back to
	 * {@link SpotifyAuth#REDIRECT_URI}, which lands in {@link #handleAuthCallback}.
	 */
	public static void startLogin(MainActivityDelegate a) {
		Context ctx = a.getContext();
		String clientId = SpotifyPrefs.getClientId();

		if (clientId.isEmpty()) {
			showSetupHelp(a);
			return;
		}

		if (a.isCarActivity()) {
			UiUtils.showToast(ctx, R.string.spotify_login_on_phone);
			return;
		}

		try {
			Intent i = new Intent(Intent.ACTION_VIEW, SpotifyAuth.buildAuthorizeUri(clientId));
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(i);
		} catch (Exception ex) {
			Log.e(ex, "Failed to open the Spotify login page");
			UiUtils.showAlert(ctx, R.string.spotify_login_no_browser);
		}
	}

	/** The browser's redirect back from the Spotify login page (see the manifest). */
	public static void handleAuthCallback(MainActivityDelegate a, Uri callback) {
		Handler h = new Handler(Looper.getMainLooper());
		new Thread(() -> {
			Exception err = null;
			try {
				SpotifyAuth.completeLogin(callback);
			} catch (Exception ex) {
				Log.e(ex, "Spotify login failed");
				err = ex;
			}
			Exception fail = err;
			h.post(() -> {
				Context ctx = a.getContext();
				if (fail != null) {
					UiUtils.showAlert(ctx, (fail.getMessage() != null) ? fail.getMessage() : fail.toString());
					return;
				}
				UiUtils.showToast(ctx, R.string.spotify_logged_in);
				ActivityFragment f = a.showFragment(R.id.spotify_import_fragment);
				if (f instanceof SpotifyImportFragment sf) sf.handler.post(sf::showMyPlaylists);
			});
		}, "SpotifyLogin").start();
	}

	/** How to get a Client ID: the one-time setup the account mode needs. */
	public static void showSetupHelp(MainActivityDelegate a) {
		Context ctx = a.getContext();
		a.createDialogBuilder(ctx)
				.setTitle(R.drawable.playlist_import, R.string.spotify_setup_title)
				.setView(createSetupStepsView(a))
				.setNeutralButton(R.string.spotify_open_dashboard, (d, i) -> openDashboard(a))
				.setPositiveButton(android.R.string.ok, (d, i) -> d.dismiss())
				.show();
	}

	/**
	 * The setup steps, with the dashboard address as a link (opens it in the in-app browser, see
	 * {@link #openDashboard}) and the redirect URI tap-to-copy, since it has to be typed exactly.
	 */
	private static View createSetupStepsView(MainActivityDelegate a) {
		Context ctx = a.getContext();
		String text = ctx.getString(R.string.spotify_setup_steps, SpotifyAuth.REDIRECT_URI);
		SpannableString span = new SpannableString(text);
		int idx = text.indexOf(DASHBOARD_LABEL);

		if (idx != -1) {
			span.setSpan(new LinkSpan(() -> openDashboard(a)), idx, idx + DASHBOARD_LABEL.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}

		idx = text.indexOf(SpotifyAuth.REDIRECT_URI);
		if (idx != -1) {
			span.setSpan(new LinkSpan(() -> copyToClipboard(ctx, SpotifyAuth.REDIRECT_URI)), idx,
					idx + SpotifyAuth.REDIRECT_URI.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}

		TextView t = new com.google.android.material.textview.MaterialTextView(ctx);
		t.setText(span);
		t.setMovementMethod(LinkMovementMethod.getInstance());
		t.setTextIsSelectable(false);
		int pad = UiUtils.toIntPx(ctx, 8);
		t.setPadding(pad, pad, pad, pad);
		ScrollView scroll = new ScrollView(ctx);
		scroll.addView(t);
		return scroll;
	}

	/**
	 * A link drawn in the text's own color (bold, underlined) instead of the theme's link color,
	 * which on several of this app's dark themes is a dark blue barely visible on the dialog.
	 */
	private static final class LinkSpan extends ClickableSpan {
		private final Runnable onClick;

		LinkSpan(Runnable onClick) {
			this.onClick = onClick;
		}

		@Override
		public void onClick(@NonNull View widget) {
			onClick.run();
		}

		@Override
		public void updateDrawState(@NonNull android.text.TextPaint ds) {
			ds.setUnderlineText(true);
			ds.setFakeBoldText(true);
		}
	}

	/**
	 * Opens the Spotify developer dashboard in the app's own Web Browser tab (installing that addon
	 * if needed), so the user can create the app and copy its Client ID without leaving zrAuto;
	 * falls back to the system browser.
	 */
	static void openDashboard(MainActivityDelegate a) {
		Context ctx = a.getContext();
		a.hideActiveMenu(); // The setup dialog, which would otherwise stay on top of the page.

		if (a.isCarActivity()) {
			UiUtils.showToast(ctx, R.string.spotify_login_on_phone);
			return;
		}

		AddonManager.get().getOrInstallAddon(WEB_BROWSER_ADDON_CLASS).main()
				.onCompletion((addon, err) -> {
					if ((err == null) && (addon != null) && Utils.openUrlInBrowserFragment(ctx, DASHBOARD_URL)) {
						UiUtils.showToast(ctx, R.string.spotify_dashboard_hint);
						return;
					}
					if (err != null) Log.e(err, "Web browser addon unavailable");
					Utils.openUrl(ctx, DASHBOARD_URL);
				});
	}

	private static void copyToClipboard(Context ctx, String text) {
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null) return;
			cm.setPrimaryClip(ClipData.newPlainText(text, text));
			UiUtils.showToast(ctx, R.string.spotify_copied, text);
		} catch (Exception ex) {
			Log.e(ex, "Failed to copy to clipboard");
		}
	}

	@Override
	public int getFragmentId() {
		return R.id.spotify_import_fragment;
	}

	@Override
	public CharSequence getTitle() {
		if (current != null) return current.name;
		return (picker != null) ? getString(R.string.spotify_my_playlists) :
				getString(R.string.spotify_import);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ImportToolBarMediator.instance;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		engine.addListener(engineListener);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.spotify_import_fragment, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		list = (RecyclerView) view;
		adapter = new Adapter();
		adapter.setHasStableIds(true);
		list.setAdapter(adapter);
		// Progress updates rebind rows many times a second while matching/engine.isImporting(); the default
		// change animation cross-fades every one of them, which is what made the cards flicker.
		if (list.getItemAnimator() instanceof SimpleItemAnimator sia) {
			sia.setSupportsChangeAnimations(false);
		}
		applyLayout(a.getPrefs().getGridViewPref(a));
		// Every scrollable screen has to reserve room for the translucent tool bar/nav bar drawn
		// over it itself -- see MainActivityDelegate.insetScrollableContent.
		a.insetScrollableContent(list);
		list.setClipChildren(false);
		rebuild();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden || (list == null)) return;
		// The grid toggle may have been flipped on another tab in the meantime.
		MainActivityDelegate a = getActivityDelegate();
		boolean g = a.getPrefs().getGridViewPref(a);
		if (g != grid) applyLayout(g);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		list = null;
	}

	@Override
	public void onStop() {
		super.onStop();
		// The app may be killed any time once in the background: save now, not in a moment.
		if (!destroyed) engine.saveNow();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		destroyed = true;
		engine.removeListener(engineListener);
		engine.setPriority(null);
		pickerExecutor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		images.evictAll();
	}

	@Override
	public boolean onBackPressed() {
		if (current != null) {
			openPlaylist(null); // Back to the picker, if it was opened from there.
			return true;
		}
		if (picker != null) {
			closePicker();
			return true;
		}
		return super.onBackPressed();
	}

	void applyLayout(boolean grid) {
		this.grid = grid;
		RecyclerView list = this.list;
		if (list == null) return;
		int spans = grid ? Math.max(2, getResources().getConfiguration().screenWidthDp / 200) : 1;
		GridLayoutManager lm = new GridLayoutManager(requireContext(), spans);
		lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				if (position >= rows.size()) return spans;
				return isCard(rows.get(position).type) ? 1 : spans;
			}
		});
		list.setLayoutManager(lm);
		adapter.notifyDataSetChanged();
	}

	private static boolean isCard(int type) {
		return (type == TYPE_PLAYLIST) || (type == TYPE_TRACK) || (type == TYPE_PICK);
	}

	private void openPlaylist(@Nullable Playlist pl) {
		current = pl;
		rebuild();
		if (list != null) list.scrollToPosition(0);
		getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		// The opened playlist's tracks are matched first.
		ensureMatcher();
	}

	// ---- Loading playlists ----

	private void promptForLinks() {
		if (destroyed || !isAdded()) return;
		Context ctx = requireContext();
		MainActivityDelegate a = getActivityDelegate();
		EditText text = a.createEditText(ctx);
		text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
				InputType.TYPE_TEXT_VARIATION_URI);
		text.setSingleLine(false);
		text.setMinLines(2);
		text.setMaxLines(6);
		text.setHint(R.string.spotify_import_link_hint);
		String clip = getClipboardText(ctx);
		if ((clip != null) && !SpotifyClient.extractRefs(clip).isEmpty()) text.setText(clip);

		a.createDialogBuilder(ctx)
				.setTitle(R.drawable.playlist_import, R.string.spotify_import)
				.setView(text)
				.setNegativeButton(android.R.string.cancel, (d, i) -> d.dismiss())
				.setPositiveButton(R.string.spotify_import_load,
						(d, i) -> addLinks(text.getText().toString()))
				.show();
	}

	@Nullable
	private static String getClipboardText(Context ctx) {
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = (cm == null) ? null : cm.getPrimaryClip();
			if ((clip == null) || (clip.getItemCount() == 0)) return null;
			CharSequence t = clip.getItemAt(0).getText();
			return (t == null) ? null : t.toString();
		} catch (Exception ex) {
			return null;
		}
	}

	private void addLinks(String text) {
		List<String> refs = SpotifyClient.extractRefs(text);

		if (refs.isEmpty()) {
			UiUtils.showAlert(requireContext(), R.string.spotify_import_no_links);
			return;
		}

		closePicker();
		for (String ref : refs) addPlaylist(ref, "", null);
		rebuild();
	}

	private void addPlaylist(String ref, String name, @Nullable String coverUrl) {
		engine.addPlaylist(ref, name, coverUrl, true);
	}

	/** Part of the import (not just opened from the picker to look at). */
	private boolean isAdded(String ref) {
		Playlist p = findPlaylist(ref);
		return (p != null) && p.included;
	}

	/** Include a playlist opened from the picker, from its own header. */
	private void includeCurrent() {
		Playlist pl = current;
		if ((pl == null) || pl.included) return;
		pickerSelected.remove(pl.ref);
		engine.setPriority(pl);
		engine.include(pl);
	}

	/**
	 * The "add" entry point: the user's own playlists when signed in (Settings' default source),
	 * otherwise the paste-a-link dialog.
	 */
	private void addPlaylists() {
		if (destroyed || !isAdded()) return;
		if (!SpotifyPrefs.isAccountSource()) {
			promptForLinks();
			return;
		}

		MainActivityDelegate a = getActivityDelegate();
		Context ctx = requireContext();

		if (SpotifyAuth.isLoggedIn()) {
			showMyPlaylists();
		} else if (SpotifyPrefs.getClientId().isEmpty()) {
			a.createDialogBuilder(ctx)
					.setTitle(R.drawable.playlist_import, R.string.spotify_setup_title)
					.setView(createSetupStepsView(a))
					.setNegativeButton(android.R.string.cancel, (d, i) -> d.dismiss())
					.setNeutralButton(R.string.spotify_paste_link, (d, i) -> promptForLinks())
					.setPositiveButton(R.string.settings, (d, i) -> a.showFragment(R.id.settings_fragment))
					.show();
		} else {
			a.createDialogBuilder(ctx)
					.setTitle(R.drawable.playlist_import, R.string.spotify_login)
					.setMessage(R.string.spotify_login_question)
					.setNegativeButton(android.R.string.cancel, (d, i) -> d.dismiss())
					.setNeutralButton(R.string.spotify_paste_link, (d, i) -> promptForLinks())
					.setPositiveButton(R.string.spotify_login, (d, i) -> startLogin(a))
					.show();
		}
	}

	/** Loads the signed-in user's playlists (plus Liked Songs) into the in-page picker. */
	private void showMyPlaylists() {
		if (destroyed || !isAdded()) return;
		UiUtils.showToast(requireContext(), R.string.spotify_loading_playlists);

		pickerExecutor.execute(() -> {
			List<SpotifyApi.PlaylistInfo> list = null;
			Exception err = null;
			try {
				list = SpotifyApi.listMyPlaylists();
			} catch (Exception ex) {
				Log.e(ex, "Failed to list Spotify playlists");
				err = ex;
			}
			List<SpotifyApi.PlaylistInfo> result = list;
			Exception fail = err;
			post(() -> {
				if (fail instanceof SpotifyAuth.AuthException) {
					addPlaylists(); // Logged out meanwhile: offers to log in again.
				} else if (fail != null) {
					String msg = (fail.getMessage() != null) ? fail.getMessage() : fail.toString();
					UiUtils.showAlert(requireContext(), getString(R.string.spotify_list_failed, msg));
				} else {
					openPicker(result);
				}
			});
		});
	}

	private void openPicker(List<SpotifyApi.PlaylistInfo> list) {
		List<SpotifyApi.PlaylistInfo> items = new ArrayList<>(list.size() + 1);
		items.add(new SpotifyApi.PlaylistInfo(SpotifyApi.LIKED_SONGS,
				getString(R.string.spotify_liked_songs), null, null, -1, true));
		items.addAll(list);
		picker = items;
		pickerSelected.clear();
		current = null;
		rebuild();
		if (this.list != null) this.list.scrollToPosition(0);
		getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
	}

	private void closePicker() {
		if (picker == null) return;
		picker = null;
		pickerSelected.clear();
		// Playlists only opened to look at, never added, aren't part of the import.
		engine.removeBrowseOnly();
		rebuild();
		getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
	}

	private void addPicked() {
		List<SpotifyApi.PlaylistInfo> items = picker;
		if (items == null) return;
		for (SpotifyApi.PlaylistInfo p : items) {
			if (pickerSelected.contains(p.ref)) addPlaylist(p.ref, p.name, p.coverUrl);
		}
		closePicker();
	}

	private void togglePicked(SpotifyApi.PlaylistInfo p) {
		if (engine.isImporting()) return;
		Playlist added = findPlaylist(p.ref);

		if ((added != null) && added.included) {
			// Unticking a playlist that was already added takes it off the import list.
			engine.remove(added);
		} else if (!pickerSelected.remove(p.ref)) {
			pickerSelected.add(p.ref);
		}

		rebuild();
	}

	/**
	 * Tapping a picker card opens the playlist to choose its tracks: it's added to the import list
	 * (loading in the background) and Back returns to the picker.
	 */
	private void openPicked(SpotifyApi.PlaylistInfo p) {
		if (engine.isImporting()) return;
		Playlist pl = findPlaylist(p.ref);

		if (pl == null) {
			// Loads just the track list; no YouTube matching until the playlist is added.
			engine.addPlaylist(p.ref, p.name, p.coverUrl, false);
			pl = findPlaylist(p.ref);
			if (pl == null) return;
		}

		openPlaylist(pl);
	}

	@Nullable
	private Playlist findPlaylist(String ref) {
		return engine.findPlaylist(ref);
	}

	private int getPickable() {
		int n = 0;
		if (picker != null) {
			for (SpotifyApi.PlaylistInfo p : picker) if (!isAdded(p.ref)) n++;
		}
		return n;
	}

	private void fetch(Playlist pl) {
		engine.fetch(pl);
	}

	// ---- Card menus ----

	private void showPlaylistMenu(Playlist pl) {
		if (engine.isImporting()) return;
		getActivityDelegate().getContextMenu().show(b -> {
			b.setTitle(pl.name.isEmpty() ? pl.ref : pl.name);
			b.addItem(R.id.playlist_rename, R.drawable.edit, R.string.spotify_rename).setHandler(i -> {
				renamePlaylist(pl);
				return true;
			});
			b.addItem(R.id.spotify_remove, R.drawable.delete, R.string.spotify_remove_from_list)
					.setHandler(i -> {
						if (current == pl) current = null;
						engine.remove(pl);
						return true;
					});
		});
	}

	private void renamePlaylist(Playlist pl) {
		UiUtils.queryText(requireContext(), R.string.spotify_rename, R.drawable.edit, pl.name)
				.onSuccess(name -> {
					if ((name == null) || name.trim().isEmpty()) return;
					pl.name = name.trim();
					pl.renamed = true;
					rebuild();
					if (current == pl) getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
				});
	}

	private void showTrackMenu(Track t) {
		if (engine.isImporting()) return;
		getActivityDelegate().getContextMenu().show(b -> {
			b.setTitle(t.displayName());
			Video m = t.match;
			if (m != null) {
				b.addItem(R.id.spotify_preview, R.drawable.play, R.string.spotify_preview)
						.setHandler(i -> {
							preview(m);
							return true;
						});
			}
			b.addItem(R.id.spotify_search_more, R.drawable.search,
					t.altExpanded ? R.string.spotify_import_hide_more : R.string.spotify_import_search_more)
					.setHandler(i -> {
						onSearchMore(t);
						return true;
					});
			b.addItem(R.id.spotify_toggle, t.selected ? me.aap.utils.R.drawable.check_box_blank :
									me.aap.utils.R.drawable.check_box,
							t.selected ? R.string.unselect_all : R.string.select)
					.setHandler(i -> {
						toggleTrack(t);
						return true;
					});
		});
	}

	/**
	 * Plays the video in the YouTube tab (the import screen keeps its state; reopen it from the
	 * Playlists tab's menu), falling back to any app that can open a YouTube link.
	 */
	private void preview(Video v) {
		previewVideo(getActivityDelegate(), v);
	}

	static void previewVideo(MainActivityDelegate a, Video v) {
		Context ctx = a.getContext();

		AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
				.onCompletion((addon, err) -> {
					if ((err != null) || (addon == null)) {
						Utils.openUrl(ctx, v.watchUrl());
						return;
					}
					if (addon instanceof VideoTitleCache c) {
						c.cacheVideoTitles(Collections.singletonMap(v.videoId, v.title));
					}
					a.getLib().getItem("youtube:" + v.videoId).main().onCompletion((item, e) -> {
						if (item instanceof MediaLib.ExternallyPlayableItem ext) {
							ActivityFragment f = a.showFragment(ext.getPlayerFragmentId());
							if (f != null) ext.loadInFragment(f, ext);
						} else {
							Utils.openUrl(ctx, v.watchUrl());
						}
					});
				});
	}

	// ---- YouTube matching ----

	/** Matching runs in the engine; the open playlist's tracks go first. */
	private void ensureMatcher() {
		engine.setPriority(current);
		engine.ensureMatcher();
	}

	private void onSearchMore(Track t) {
		if (engine.isImporting()) return;

		if (t.altExpanded) {
			t.altExpanded = false;
		} else {
			t.altExpanded = true;

			engine.searchMore(t);
		}

		rebuild();
	}

	private void onAlternativeSelected(Track t, Video v) {
		if (engine.isImporting()) return;
		t.match = v;
		t.userPicked = true;
		t.matchState = Track.MATCH_FOUND;
		t.selected = true;
		t.altExpanded = false;
		rebuild();
	}

	// ---- Import ----

	/** Runs in the engine, so it carries on in the background (see SpotifyImportService). */
	private void startImport() {
		if (!engine.startImport()) {
			UiUtils.showToast(requireContext(), R.string.spotify_import_nothing_selected);
		}
	}

	private void cancelImport() {
		engine.cancelImport();
	}

	private void onImportDone(SpotifyImportEngine.ImportResult r) {
		if (destroyed || !isAdded()) return;
		Context ctx = requireContext();
		MainActivityDelegate a = getActivityDelegate();

		if (r.status == SpotifyImportEngine.ImportResult.DONE) {
			MediaLibFragment f = a.getMediaLibFragment(R.id.playlists_fragment);
			if (f != null) f.getAdapter().reload();
		}

		// A dialog only while this screen is showing; otherwise a toast (plus the notification).
		if (isHidden() || (r.status == SpotifyImportEngine.ImportResult.CANCELLED)) {
			UiUtils.showToast(ctx, r.getMessage(ctx));
		} else if (r.status == SpotifyImportEngine.ImportResult.DONE) {
			UiUtils.showInfo(ctx, r.getMessage(ctx));
		} else {
			UiUtils.showAlert(ctx, r.getMessage(ctx));
		}
	}

	// ---- Selection ----

	private void toggleAll() {
		if (engine.isImporting()) return;

		if (picker != null) {
			boolean all = pickerSelected.size() == getPickable();
			pickerSelected.clear();
			if (!all) {
				for (SpotifyApi.PlaylistInfo p : picker) if (!isAdded(p.ref)) pickerSelected.add(p.ref);
			}
		} else if (current != null) {
			current.setAllSelected(!current.isAllSelected());
		} else {
			boolean all = isEverythingSelected();
			for (Playlist pl : playlists) if (pl.included) pl.setAllSelected(!all);
		}

		rebuild();
	}

	private void toggleTrack(Track t) {
		if (engine.isImporting()) return;
		t.selected = !t.selected;
		rebuild();
	}

	private boolean isEverythingSelected() {
		boolean any = false;
		for (Playlist pl : playlists) {
			if (!pl.included || (pl.state != Playlist.STATE_LOADED)) continue;
			any = true;
			if (!pl.isAllSelected()) return false;
		}
		return any;
	}

	private int getTotalSelected() {
		return engine.getTotalSelected();
	}

	private int getImportCount() {
		return engine.getImportCount();
	}

	private void setMatchingPaused(boolean paused) {
		engine.setMatchingPaused(paused);
	}

	// ---- Helpers ----

	private void post(Runnable r) {
		handler.post(() -> {
			if (!destroyed) r.run();
		});
	}

	private void rebuild() {
		engine.scheduleSave();
		rows.clear();
		rows.add(new Row(TYPE_HEADER, null, null, null, null));

		if ((current == null) && (picker != null)) {
			for (SpotifyApi.PlaylistInfo p : picker) rows.add(new Row(TYPE_PICK, null, null, null, p));
		} else if (current == null) {
			if (playlists.isEmpty()) rows.add(new Row(TYPE_EMPTY, null, null, null, null));
			for (Playlist pl : playlists) {
				if (pl.included) rows.add(new Row(TYPE_PLAYLIST, pl, null, null, null));
			}
		} else {
			for (Track t : current.tracks) {
				rows.add(new Row(TYPE_TRACK, current, t, null, null));
				if (!t.altExpanded) continue;

				if (t.altSearching || (t.alternatives == null) || t.alternatives.isEmpty()) {
					rows.add(new Row(TYPE_ALT_STATUS, current, t, null, null));
				} else {
					for (Video v : t.alternatives) rows.add(new Row(TYPE_ALT, current, t, v, null));
				}
			}
		}

		if (adapter != null) adapter.notifyDataSetChanged();
	}

	private void refreshHeader() {
		if (adapter != null) adapter.notifyItemChanged(0);
	}

	private void refreshTrack(Track t) {
		if ((adapter == null) || (current == null)) return;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			if ((r.track == t) && (r.type == TYPE_TRACK)) {
				adapter.notifyItemChanged(i);
				return;
			}
		}
	}

	/** The playlist cards show how many tracks are matched so far. */
	private void refreshPlaylistOf(Track t) {
		if ((adapter == null) || (current != null) || (picker != null)) return;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			if ((r.type == TYPE_PLAYLIST) && (r.playlist != null) && r.playlist.tracks.contains(t)) {
				adapter.notifyItemChanged(i);
				return;
			}
		}
	}

	private void loadImage(ImageView v, @Nullable String url, @DrawableRes int placeholder) {
		Object tag = v.getTag();
		v.setTag(url);
		Bitmap cached = (url == null) ? null : images.get(url);

		if (cached != null) {
			v.setImageTintList(null);
			v.setImageBitmap(cached);
			return;
		}

		// Already showing (or loading) this very image: don't flash the placeholder over it.
		if ((url != null) && url.equals(tag)) return;
		v.setImageResource(placeholder);
		v.setImageTintList(placeholderTint(v.getContext()));
		if (url == null) return;

		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main()
				.onSuccess(bm -> {
					if (bm == null) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) {
						v.setImageTintList(null);
						v.setImageBitmap(bm);
					}
				});
	}

	/**
	 * The placeholder icons are black vectors: tinted with the theme's primary text color so they
	 * stay visible on dark themes as well as light ones (a real thumbnail clears the tint).
	 */
	static android.content.res.ColorStateList placeholderTint(Context ctx) {
		android.content.res.TypedArray ta =
				ctx.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
		android.content.res.ColorStateList c = ta.getColorStateList(0);
		ta.recycle();
		return c;
	}

	private static final class Row {
		final int type;
		@Nullable
		final Playlist playlist;
		@Nullable
		final Track track;
		@Nullable
		final Video video;
		@Nullable
		final SpotifyApi.PlaylistInfo pick;

		Row(int type, @Nullable Playlist playlist, @Nullable Track track, @Nullable Video video,
				@Nullable SpotifyApi.PlaylistInfo pick) {
			this.type = type;
			this.playlist = playlist;
			this.track = track;
			this.video = video;
			this.pick = pick;
		}

		/** Stable across rebuilds, so rebinding keeps each row's view instead of re-creating it. */
		long stableId() {
			long h = switch (type) {
				case TYPE_PLAYLIST -> System.identityHashCode(playlist);
				case TYPE_TRACK, TYPE_ALT_STATUS -> System.identityHashCode(track);
				case TYPE_ALT -> 31L * System.identityHashCode(track) + video.videoId.hashCode();
				case TYPE_PICK -> pick.ref.hashCode();
				default -> 0;
			};
			return ((long) type << 40) ^ (h & 0xFFFFFFFFFFL);
		}
	}

	private static final class Holder extends RecyclerView.ViewHolder {
		@Nullable
		final ImageView check;
		@Nullable
		final ImageView thumb;
		@Nullable
		final ProgressBar thumbProgress;
		@Nullable
		final TextView title;
		@Nullable
		final TextView subtitle;
		@Nullable
		final TextView detail;
		@Nullable
		final View searchMore;
		@Nullable
		final View preview;
		@Nullable
		final TextView status;

		Holder(View v) {
			super(v);
			check = v.findViewById(R.id.si_check);
			thumb = v.findViewById(R.id.si_thumb);
			thumbProgress = v.findViewById(R.id.si_thumb_progress);
			title = v.findViewById(R.id.si_title);
			subtitle = v.findViewById(R.id.si_subtitle);
			detail = v.findViewById(R.id.si_detail);
			searchMore = v.findViewById(R.id.si_search_more);
			preview = v.findViewById(R.id.si_preview);
			status = v.findViewById(R.id.si_status);
		}

		/** The card in grid mode, the row itself in list mode. */
		View clickTarget() {
			View card = itemView.findViewById(R.id.si_card);
			return (card != null) ? card : itemView;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@Override
		public long getItemId(int position) {
			return rows.get(position).stableId();
		}

		@Override
		public int getItemViewType(int position) {
			int type = rows.get(position).type;
			return (grid && isCard(type)) ? type + GRID : type;
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inf = LayoutInflater.from(parent.getContext());
			int layout;

			switch (viewType) {
				case TYPE_HEADER:
					layout = R.layout.spotify_import_header;
					break;
				case TYPE_PLAYLIST + GRID:
				case TYPE_TRACK + GRID:
				case TYPE_PICK + GRID:
					layout = R.layout.spotify_import_grid_item;
					break;
				case TYPE_ALT:
					layout = R.layout.spotify_import_alt_item;
					break;
				case TYPE_ALT_STATUS:
				case TYPE_EMPTY:
					return new Holder(createMessageView(parent.getContext()));
				default:
					layout = R.layout.spotify_import_list_item;
			}

			return new Holder(inf.inflate(layout, parent, false));
		}

		private View createMessageView(Context ctx) {
			TextView t = new com.google.android.material.textview.MaterialTextView(ctx);
			int pad = UiUtils.toIntPx(ctx, 16);
			t.setPadding(UiUtils.toIntPx(ctx, 64), pad / 2, pad, pad / 2);
			t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			t.setId(R.id.si_title);
			return t;
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			Row r = rows.get(position);

			switch (r.type) {
				case TYPE_HEADER -> bindHeader(h);
				case TYPE_PLAYLIST -> bindPlaylist(h, r.playlist);
				case TYPE_TRACK -> bindTrack(h, r.track);
				case TYPE_ALT -> bindAlt(h, r.track, r.video);
				case TYPE_PICK -> bindPick(h, r.pick);
				case TYPE_ALT_STATUS -> {
					TextView t = (TextView) h.itemView;
					t.setText(r.track.altSearching ? R.string.spotify_import_searching :
							R.string.spotify_import_no_results);
				}
				case TYPE_EMPTY -> {
					TextView t = (TextView) h.itemView;
					t.setText(R.string.spotify_import_empty);
				}
			}
		}

		private void bindHeader(Holder h) {
			View v = h.itemView;
			TextView title = v.findViewById(R.id.si_header_title);
			TextView summary = v.findViewById(R.id.si_header_summary);
			TextView note = v.findViewById(R.id.si_header_note);
			View progressGroup = v.findViewById(R.id.si_progress_group);
			ProgressBar progress = v.findViewById(R.id.si_progress_bar);
			TextView progressLabel = v.findViewById(R.id.si_progress_text);
			TextView progressAction = v.findViewById(R.id.si_progress_action);
			progressAction.setVisibility(View.GONE);
			TextView selectAll = v.findViewById(R.id.si_select_all);
			TextView addLink = v.findViewById(R.id.si_add_link);
			View myPlaylists = v.findViewById(R.id.si_my_playlists);
			TextView importBtn = v.findViewById(R.id.si_import);
			boolean account = SpotifyPrefs.isAccountSource();
			int total = getTotalSelected();

			if ((picker != null) && (current == null)) {
				int pickable = getPickable();
				title.setText(R.string.spotify_my_playlists);
				summary.setText(getString(R.string.spotify_picker_summary, pickerSelected.size(),
						pickable));
				note.setVisibility(View.VISIBLE);
				note.setText(R.string.spotify_picker_hint);
				selectAll.setText(((pickable > 0) && (pickerSelected.size() == pickable)) ?
						R.string.unselect_all : R.string.select_all);
				selectAll.setEnabled(true);
				selectAll.setOnClickListener(b -> toggleAll());
				addLink.setVisibility(View.VISIBLE);
				addLink.setText(android.R.string.cancel);
				addLink.setEnabled(true);
				addLink.setOnClickListener(b -> closePicker());
				myPlaylists.setVisibility(View.GONE);
				progressGroup.setVisibility(View.GONE);
				importBtn.setText(getString(R.string.spotify_picker_add, pickerSelected.size()));
				importBtn.setEnabled(!pickerSelected.isEmpty());
				importBtn.setOnClickListener(b -> addPicked());
				return;
			}

			addLink.setText(R.string.spotify_import_add_link);

			if (current != null) {
				title.setText(current.name.isEmpty() ? current.ref : current.name);
				if (current.state == Playlist.STATE_LOADING) {
					summary.setText(R.string.spotify_import_loading);
				} else if (current.state == Playlist.STATE_FAILED) {
					summary.setText(getString(R.string.spotify_import_failed, current.error));
				} else {
					summary.setText(getString(R.string.spotify_import_selected,
							current.getSelectedCount(), current.tracks.size()));
				}
				note.setVisibility(View.GONE);
				selectAll.setText(current.isAllSelected() ? R.string.unselect_all : R.string.select_all);
				addLink.setVisibility(View.GONE);
				myPlaylists.setVisibility(View.GONE);
			} else {
				int loaded = 0;
				for (Playlist pl : playlists) if (pl.included && (pl.state == Playlist.STATE_LOADED)) loaded++;
				title.setText(R.string.spotify_import);
				summary.setText(getString(R.string.spotify_import_summary, loaded, total));
				note.setVisibility(View.VISIBLE);
				note.setText(account ? R.string.spotify_import_intro_account :
						R.string.spotify_import_intro);
				selectAll.setText(isEverythingSelected() ? R.string.unselect_all : R.string.select_all);
				addLink.setVisibility(View.VISIBLE);
				myPlaylists.setVisibility(account ? View.VISIBLE : View.GONE);
			}

			selectAll.setEnabled(!engine.isImporting());
			addLink.setEnabled(!engine.isImporting());
			myPlaylists.setEnabled(!engine.isImporting());
			selectAll.setOnClickListener(b -> toggleAll());
			addLink.setOnClickListener(b -> promptForLinks());
			myPlaylists.setOnClickListener(b -> addPlaylists());

			if ((current != null) && !current.included) {
				progressGroup.setVisibility(View.GONE);
				note.setVisibility(View.VISIBLE);
				note.setText(R.string.spotify_browse_note);
				importBtn.setText(R.string.spotify_add_this_playlist);
				importBtn.setEnabled(!engine.isImporting() && (current.state == Playlist.STATE_LOADED));
				importBtn.setOnClickListener(b -> includeCurrent());
			} else if (engine.isImporting()) {
				progressGroup.setVisibility(View.VISIBLE);
				progress.setIndeterminate(engine.isSaving() || (engine.getProgressTotal() == 0));
				progress.setMax(Math.max(1, engine.getProgressTotal()));
				progress.setProgress(engine.getProgressDone());
				progressLabel.setText((engine.getProgressText() != null) ? engine.getProgressText() :
						getString(R.string.spotify_import_preparing));
				importBtn.setText(android.R.string.cancel);
				importBtn.setEnabled(!engine.isSaving() && !engine.isCancelling());
				importBtn.setOnClickListener(b -> cancelImport());
			} else {
				bindMatchingProgress(progressGroup, progress, progressLabel, progressAction);
				int count = getImportCount();
				importBtn.setText(getString(R.string.spotify_import_import, count));
				importBtn.setEnabled(count > 0);
				importBtn.setOnClickListener(b -> startImport());
			}
		}

		/** While not engine.isImporting(), the header shows the background matching's progress, if any. */
		private void bindMatchingProgress(View group, ProgressBar progress, TextView label,
																			TextView action) {
			int[] p = engine.getMatchProgress();
			int done = p[0];
			int all = p[1];

			if ((all == 0) || (done >= all)) {
				group.setVisibility(View.GONE);
				return;
			}

			group.setVisibility(View.VISIBLE);
			progress.setIndeterminate(false);
			progress.setMax(all);
			progress.setProgress(done);
			label.setText(engine.isMatchingPaused() ? getString(R.string.spotify_matching_stopped, done, all) :
					getString(R.string.spotify_matching_bg, done, all));
			action.setVisibility(View.VISIBLE);
			action.setText(engine.isMatchingPaused() ? R.string.spotify_matching_resume :
					R.string.spotify_matching_stop);
			action.setOnClickListener(b -> setMatchingPaused(!engine.isMatchingPaused()));
		}

		private void bindPlaylist(Holder h, Playlist pl) {
			Context ctx = h.itemView.getContext();
			boolean loaded = pl.state == Playlist.STATE_LOADED;
			setText(h.title, pl.name.isEmpty() ? pl.ref : pl.name);
			setText(h.subtitle, (pl.state == Playlist.STATE_FAILED) ?
					ctx.getString(R.string.spotify_import_retry) : pl.owner);

			switch (pl.state) {
				case Playlist.STATE_LOADING ->
						setText(h.detail, ctx.getString(R.string.spotify_import_loading));
				case Playlist.STATE_FAILED -> setText(h.detail,
						ctx.getString(R.string.spotify_import_failed, pl.error));
				default -> {
					String d = ctx.getString(R.string.spotify_import_selected, pl.getSelectedCount(),
							pl.tracks.size());
					if (!pl.fullList && (pl.tracks.size() >= 100)) {
						d += " • " + ctx.getString(R.string.spotify_first_100);
					}
					setText(h.detail, d);
				}
			}

			if (h.thumb != null) loadImage(h.thumb, pl.coverUrl, R.drawable.playlist);
			if (h.thumbProgress != null) {
				h.thumbProgress.setVisibility((pl.state == Playlist.STATE_LOADING) ?
						View.VISIBLE : View.GONE);
			}
			if (h.searchMore != null) h.searchMore.setVisibility(View.GONE);
			if (h.status != null) h.status.setVisibility(View.GONE);

			if (h.check != null) {
				h.check.setVisibility(loaded ? View.VISIBLE : View.INVISIBLE);
				int sel = pl.getSelectedCount();
				h.check.setImageResource((sel == 0) ? me.aap.utils.R.drawable.check_box_blank :
						me.aap.utils.R.drawable.check_box);
				// Partly selected: a dimmed check.
				h.check.setAlpha(((sel > 0) && (sel < pl.tracks.size())) ? 0.5f : 1f);
				h.check.setOnClickListener(v -> {
					if (engine.isImporting() || !loaded) return;
					pl.setAllSelected(!pl.isAllSelected());
					rebuild();
				});
			}

			View target = h.clickTarget();
			target.setOnClickListener(v -> {
				if (pl.state == Playlist.STATE_LOADED) {
					openPlaylist(pl);
				} else if ((pl.state == Playlist.STATE_FAILED) && !engine.isImporting()) {
					fetch(pl);
					rebuild();
				}
			});
			target.setOnLongClickListener(v -> {
				showPlaylistMenu(pl);
				return true;
			});
		}

		private void bindTrack(Holder h, Track t) {
			Context ctx = h.itemView.getContext();
			Video m = t.match;
			setText(h.title, (m != null) ? m.title : t.title);
			setText(h.subtitle, t.displayName());

			String detail;
			switch (t.matchState) {
				case Track.MATCH_SEARCHING -> detail = ctx.getString(R.string.spotify_import_searching);
				case Track.MATCH_NOT_FOUND -> detail = ctx.getString(R.string.spotify_import_not_found);
				case Track.MATCH_FAILED -> detail = ctx.getString(R.string.spotify_import_search_failed);
				default -> detail = (m != null) ? videoDetail(m) : null;
			}
			setText(h.detail, detail);

			if (h.thumb != null) {
				// Spotify's album art until a YouTube video is matched (e.g. while just browsing a
				// playlist from the picker, which doesn't search YouTube at all).
				loadImage(h.thumb, (m != null) ? m.thumbnailUrl() : t.imageUrl, R.drawable.audiotrack);
			}
			if (h.thumbProgress != null) {
				h.thumbProgress.setVisibility((t.matchState == Track.MATCH_SEARCHING) ?
						View.VISIBLE : View.GONE);
			}
			if (h.status != null) {
				// In a list row the small thumbnail's centred spinner already says "matching", and
				// the badge would sit right on top of it; the grid card is big enough for both.
				if (!grid && (t.matchState == Track.MATCH_SEARCHING)) h.status.setVisibility(View.GONE);
				else bindStatus(h.status, t);
			}

			if (h.check != null) {
				h.check.setVisibility(View.VISIBLE);
				h.check.setAlpha(1f);
				h.check.setImageResource(t.selected ? me.aap.utils.R.drawable.check_box :
						me.aap.utils.R.drawable.check_box_blank);
				h.check.setOnClickListener(v -> toggleTrack(t));
			}

			if (h.searchMore != null) {
				h.searchMore.setVisibility(View.VISIBLE);
				h.searchMore.setEnabled(!engine.isImporting());
				if (h.searchMore instanceof TextView sm) {
					sm.setText(t.altExpanded ? R.string.spotify_import_hide_more :
							R.string.spotify_import_search_more);
				} else {
					h.searchMore.setAlpha(t.altExpanded ? 0.5f : 1f);
				}
				h.searchMore.setOnClickListener(v -> onSearchMore(t));
			}

			View target = h.clickTarget();
			target.setOnClickListener(v -> toggleTrack(t));
			target.setOnLongClickListener(v -> {
				showTrackMenu(t);
				return true;
			});
		}

		/**
		 * The match badge on a track's thumbnail: green "Matched" (or "Your pick") with the
		 * YouTube icon, grey while not matched yet (the picture is still Spotify's album art) or
		 * being searched, red when YouTube found nothing. Fixed colours: it's always drawn over an
		 * image, never over the theme's own background.
		 */
		private void bindStatus(TextView v, Track t) {
			int text;
			int color;
			boolean yt = false;

			switch (t.matchState) {
				case Track.MATCH_SEARCHING -> {
					text = R.string.spotify_status_matching;
					color = 0xCC424242;
				}
				case Track.MATCH_NOT_FOUND, Track.MATCH_FAILED -> {
					text = (t.matchState == Track.MATCH_FAILED) ? R.string.spotify_status_failed :
							R.string.spotify_status_no_match;
					color = 0xE6C62828;
				}
				default -> {
					if (t.match != null) {
						text = t.userPicked ? R.string.spotify_status_picked : R.string.spotify_status_matched;
						color = 0xE62E7D32;
						yt = true;
					} else {
						text = R.string.spotify_status_unmatched;
						color = 0xCC424242;
					}
				}
			}

			v.setVisibility(View.VISIBLE);
			v.setText(text);
			v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));

			if (yt) {
				android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(
						v.getContext(), R.drawable.youtube);
				if (d != null) {
					int size = UiUtils.toIntPx(v.getContext(), 14);
					d = d.mutate();
					d.setBounds(0, 0, size, size);
					d.setTint(0xFFFFFFFF);
				}
				v.setCompoundDrawablesRelative(d, null, null, null);
			} else {
				v.setCompoundDrawablesRelative(null, null, null, null);
			}
		}

		private void bindAlt(Holder h, Track t, Video v) {
			setText(h.title, v.title);
			setText(h.detail, videoDetail(v));
			if (h.thumb != null) loadImage(h.thumb, v.thumbnailUrl(), R.drawable.video);
			boolean chosen = (t.match != null) && t.match.videoId.equals(v.videoId);
			if (h.check != null) h.check.setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
			if (h.preview != null) h.preview.setOnClickListener(x -> preview(v));
			h.itemView.setOnClickListener(x -> onAlternativeSelected(t, v));
			h.itemView.setOnLongClickListener(x -> {
				preview(v);
				return true;
			});
		}

		private void bindPick(Holder h, SpotifyApi.PlaylistInfo p) {
			Context ctx = h.itemView.getContext();
			Playlist added = findPlaylist(p.ref);
			boolean selected = ((added != null) && added.included) || pickerSelected.contains(p.ref);
			setText(h.title, p.name);
			if ((added != null) && added.included && (added.state == Playlist.STATE_LOADED)) {
				setText(h.subtitle, ctx.getString(R.string.spotify_import_selected,
						added.getSelectedCount(), added.tracks.size()));
			} else if ((added != null) && (added.state == Playlist.STATE_FAILED)) {
				setText(h.subtitle, ctx.getString(R.string.spotify_import_failed, added.error));
			} else {
				setText(h.subtitle, ((added != null) && added.included) ?
						ctx.getString(R.string.spotify_already_added) : p.owner);
			}

			String d = (p.total >= 0) ? ctx.getString(R.string.spotify_tracks, p.total) : null;
			if (!p.full) {
				String f = ctx.getString(R.string.spotify_first_100);
				d = (d == null) ? f : (d + " • " + f);
			}
			setText(h.detail, d);

			if (h.thumb != null) {
				loadImage(h.thumb, p.coverUrl, SpotifyApi.LIKED_SONGS.equals(p.ref) ?
						R.drawable.favorite_filled : R.drawable.playlist);
			}
			if (h.thumbProgress != null) h.thumbProgress.setVisibility(View.GONE);
			if (h.searchMore != null) h.searchMore.setVisibility(View.GONE);
			if (h.status != null) h.status.setVisibility(View.GONE);

			if (h.check != null) {
				h.check.setVisibility(View.VISIBLE);
				h.check.setAlpha(1f);
				h.check.setImageResource(selected ? me.aap.utils.R.drawable.check_box :
						me.aap.utils.R.drawable.check_box_blank);
				h.check.setOnClickListener(v -> togglePicked(p));
			}

			View target = h.clickTarget();
			target.setOnClickListener(v -> openPicked(p));
			target.setOnLongClickListener(v -> {
				togglePicked(p);
				return true;
			});
		}

		private String videoDetail(Video v) {
			if ((v.channel != null) && (v.durationText != null)) {
				return v.channel + " • " + v.durationText;
			}
			return (v.channel != null) ? v.channel : ((v.durationText != null) ? v.durationText : "");
		}

		private void setText(@Nullable TextView t, @Nullable CharSequence text) {
			if (t == null) return;
			t.setText(text);
			t.setVisibility(((text == null) || (text.length() == 0)) ? View.GONE : View.VISIBLE);
		}
	}

	/** Back button, title, and the same list/grid toggle the media tabs have. */
	private static final class ImportToolBarMediator implements ToolBarView.Mediator.BackTitle {
		static final ImportToolBarMediator instance = new ImportToolBarMediator();

		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			ToolBarView.Mediator.BackTitle.super.enable(tb, f);
			MainActivityDelegate a = MainActivityDelegate.get(tb.getContext());
			boolean grid = a.getPrefs().getGridViewPref(a);
			addButton(tb, grid ? R.drawable.view_list : R.drawable.view_grid, this::onGridClick,
					R.id.tool_grid);
		}

		private void onGridClick(View v) {
			MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
			boolean grid = !a.getPrefs().getGridViewPref(a);
			a.getPrefs().setGridViewPref(a, grid);
			((ImageView) v).setImageResource(grid ? R.drawable.view_list : R.drawable.view_grid);
			if (a.getActiveFragment() instanceof SpotifyImportFragment f) f.applyLayout(grid);
		}
	}
}
