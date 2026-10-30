package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.support.v4.media.session.PlaybackStateCompat;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.SidePanel;
import me.aap.utils.log.Log;

/**
 * YouTube's search and Up next as a {@link SidePanel} beside fullscreen video: a pair of pills to pick which, a search field with
 * chips (past searches, or suggestions while typing), and the videos as cards (picture, title, channel and length) with chips for
 * what can be done with each: play it now, or put it next in Up next (and take a queued one out). The same actions as the panel
 * inside the YouTube tab ({@link YoutubeSearchPanel}), whose search and queue it uses.
 */
final class YoutubePanel implements SidePanel.Body {
	private static final String ID = "youtube";
	private static final int MAX_RESULTS = 25;
	private static final int MAX_CHIPS = 12;
	private static final int MAX_LIBRARY_RESULTS = 12;
	private static final long SUGGEST_DELAY_MS = 220;
	private static final int T_CARD = 0;
	private static final int T_HEADER = 1;
	private static final int T_NOTE = 2;
	private static final int K_RESULT = 0;
	private static final int K_QUEUED = 1;
	private static final int K_LIBRARY = 2;
	private static final int K_LIST = 3;
	@Nullable
	private static YoutubePanel current;

	private final MainActivityDelegate a;
	private final YoutubeAddon addon;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final ExecutorService suggestExecutor = Executors.newSingleThreadExecutor();
	private final LruCache<String, Bitmap> images = new LruCache<>(60);
	private final List<Row> rows = new ArrayList<>();
	private final List<Video> results = new ArrayList<>();
	private final List<PlayableItem> libraryResults = new ArrayList<>();
	private final List<PlayableItem> listItems = new ArrayList<>();
	@Nullable
	private String listName;
	private boolean listShuffled;
	private int listGeneration;
	private final Runnable queueListener = () -> handler.post(this::onQueueChanged);
	private final Runnable listReload = this::loadList;
	private final MediaSessionCallback.Listener playbackListener = new MediaSessionCallback.Listener() {
		@Override
		public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
			// A skip reports several states in a row: looked at once, shortly after.
			handler.removeCallbacks(listReload);
			handler.postDelayed(listReload, 300);
		}
	};
	private SidePanel p;
	private boolean queueMode;
	private boolean searching;
	private boolean failed;
	private boolean searched;
	private String query = "";
	private int generation;
	private int suggestGeneration;
	private String typing = "";
	private Pill pillSearch;
	private Pill pillQueue;
	private LinearLayout controls;
	private EditText input;
	private HorizontalScrollView chipScroll;
	private LinearLayout chips;
	private RecyclerView list;
	private final Adapter adapter = new Adapter();
	private Runnable pendingSuggest;

	private YoutubePanel(MainActivityDelegate a, YoutubeAddon addon, boolean queueMode) {
		this.a = a;
		this.addon = addon;
		this.queueMode = queueMode;
	}

	/** Opens the panel on Search or on Up next; with it open already, the other action switches it, and the same one closes it. */
	static void show(MainActivityDelegate a, YoutubeAddon addon, boolean queue) {
		YoutubePanel c = current;
		if ((c != null) && SidePanel.isOpen(ID)) {
			if (c.queueMode == queue) SidePanel.dismissOpen();
			else c.setMode(queue, true);
			return;
		}
		YoutubePanel panel = new YoutubePanel(a, addon, queue);
		current = panel;
		SidePanel.toggle(a, ID, queue ? R.drawable.up_next : R.drawable.search,
				queue ? R.string.action_youtube_up_next : R.string.action_youtube_search,
				MainActivityPrefs.SIDE_PANEL_YOUTUBE_PERCENT, panel);
	}

	private static boolean tapQueues() {
		return MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.YT_SEARCH_TAP_QUEUES);
	}

	// ---------------------------------------------------------------------------------------------

	@Override
	public View create(SidePanel panel) {
		this.p = panel;
		int gap = p.dp(8);
		boolean car = a.isCarActivity();
		LinearLayout root = new LinearLayout(p.ctx);
		root.setOrientation(LinearLayout.VERTICAL);

		LinearLayout pills = new LinearLayout(p.ctx);
		pills.setOrientation(LinearLayout.HORIZONTAL);
		pills.setPadding(p.dp(4), 0, p.dp(4), gap);
		pillSearch = new Pill(p.ctx.getString(R.string.action_youtube_search), () -> setMode(false, true));
		pillQueue = new Pill(p.ctx.getString(R.string.youtube_up_next), () -> setMode(true, true));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, p.dp(38), 1f);
		plp.setMarginEnd(gap);
		pills.addView(pillSearch.view, plp);
		pills.addView(pillQueue.view, new LinearLayout.LayoutParams(0, p.dp(38), 1f));
		root.addView(pills, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The field and the chips come and go with the tab: their room opens and closes smoothly.
		controls = new LinearLayout(p.ctx);
		controls.setOrientation(LinearLayout.VERTICAL);
		LayoutTransition lt = new LayoutTransition();
		lt.setDuration(200);
		lt.enableTransitionType(LayoutTransition.CHANGING);
		controls.setLayoutTransition(lt);
		root.addView(controls, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		input = new EditText(p.ctx);
		input.setHint(R.string.youtube_search_hint);
		input.setHintTextColor(p.secondary);
		input.setTextColor(p.primary);
		input.setTextSize(15);
		input.setSingleLine(true);
		input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		input.setMinHeight(p.dp(44));
		input.setPadding(p.dp(16), 0, p.dp(16), 0);
		input.setBackground(p.pressable(p.chipFill, 22));
		// No keyboard in the car: the chips and the cards are picked with the wheel.
		input.setFocusable(!car);
		input.setFocusableInTouchMode(!car);
		input.setOnEditorActionListener((v, id, e) -> {
			if (id == EditorInfo.IME_ACTION_SEARCH) {
				search(input.getText().toString().trim());
				return true;
			}
			return false;
		});
		input.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int st, int c, int af) {
			}

			@Override
			public void onTextChanged(CharSequence s, int st, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable e) {
				onTyping(e.toString().trim());
			}
		});
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		ilp.setMargins(p.dp(4), 0, p.dp(4), gap);
		controls.addView(input, ilp);

		chips = new LinearLayout(p.ctx);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setPadding(p.dp(4), 0, p.dp(4), gap);
		chipScroll = new HorizontalScrollView(p.ctx);
		chipScroll.setHorizontalScrollBarEnabled(false);
		chipScroll.addView(chips, new HorizontalScrollView.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		controls.addView(chipScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		list = new RecyclerView(p.ctx);
		list.setLayoutManager(new LinearLayoutManager(p.ctx));
		list.setAdapter(adapter);
		list.setClipToPadding(false);
		list.setPadding(p.dp(4), 0, p.dp(4), gap);
		root.addView(list, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		addon.addUpNextListener(queueListener);
		a.getMediaSessionCallback().addBroadcastListener(playbackListener);
		setMode(queueMode, false);
		return root;
	}

	@Override
	public void onClosed() {
		addon.removeUpNextListener(queueListener);
		a.getMediaSessionCallback().removeBroadcastListener(playbackListener);
		executor.shutdownNow();
		suggestExecutor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		if (current == this) current = null;
	}

	// ---------------------------------------------------------------------------------------------
	// Tabs

	private void setMode(boolean queue, boolean animate) {
		boolean changed = (queue != queueMode) || !animate;
		queueMode = queue;
		pillSearch.set(!queue, animate);
		pillQueue.set(queue, animate);
		input.setVisibility(queue ? View.GONE : View.VISIBLE);
		chipScroll.setVisibility((queue || chips.getChildCount() == 0) ? View.GONE : View.VISIBLE);
		if (!changed) return;
		Runnable swap = () -> {
			if (queue) {
				loadQueueRows();
				loadList();
			} else {
				fillChips(input.getText().toString().trim());
				rebuildSearchRows();
			}
		};
		if (!animate) {
			swap.run();
			return;
		}
		// The cards fade out, the other tab's come in.
		list.animate().cancel();
		list.animate().alpha(0f).setDuration(110).withEndAction(() -> {
			swap.run();
			list.scrollToPosition(0);
			list.animate().alpha(1f).setDuration(200).start();
		}).start();
	}

	/** A tab's pill: filled with the accent while picked; the fill and the text colour glide when it changes. */
	private final class Pill {
		final TextView view;
		private final GradientDrawable fill = new GradientDrawable();
		private boolean on;
		private ValueAnimator anim;

		Pill(String label, Runnable onClick) {
			view = p.text(label, 14, p.primary, true);
			view.setGravity(Gravity.CENTER);
			view.setSingleLine(true);
			view.setClickable(true);
			view.setFocusable(true);
			view.setOnClickListener(v -> onClick.run());
			fill.setCornerRadius(p.dp(19));
			fill.setColor(p.chipFill);
			view.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), fill, null));
		}

		void set(boolean picked, boolean animate) {
			int toFill = picked ? p.accent : p.chipFill;
			int toText = picked ? p.onAccent : p.primary;
			if (anim != null) anim.cancel();
			if (!animate || (picked == on)) {
				on = picked;
				fill.setColor(toFill);
				view.setTextColor(toText);
				return;
			}
			on = picked;
			int fromFill = ((android.graphics.drawable.GradientDrawable) fill).getColor().getDefaultColor();
			int fromText = view.getCurrentTextColor();
			anim = ValueAnimator.ofFloat(0f, 1f);
			anim.setDuration(220);
			ArgbEvaluator ev = new ArgbEvaluator();
			anim.addUpdateListener(an -> {
				float t = an.getAnimatedFraction();
				fill.setColor((Integer) ev.evaluate(t, fromFill, toFill));
				view.setTextColor((Integer) ev.evaluate(t, fromText, toText));
			});
			anim.start();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Search

	private void search(String q) {
		if (q.isEmpty()) return;
		query = q;
		generation++;
		int gen = generation;
		searching = true;
		failed = false;
		searched = true;
		results.clear();
		libraryResults.clear();
		addon.addSearchHistory(q);
		hideKeyboard();
		rebuildSearchRows();
		// What is already saved, found while the search runs.
		if (MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.YT_SEARCH_LIBRARY)) {
			handler.postDelayed(() -> {
				if (gen == generation) searchLibrary(q, gen);
			}, 60);
		}
		executor.execute(() -> {
			List<Video> found = null;
			try {
				found = YoutubeSearch.search(q, MAX_RESULTS);
			} catch (Exception ex) {
				Log.e(ex, "YouTube search failed: ", q);
			}
			List<Video> result = found;
			handler.post(() -> {
				if (gen != generation) return;
				searching = false;
				failed = (result == null);
				results.clear();
				if (result != null) {
					results.addAll(result);
					// Kept, so a result added to Favorites later has its channel and length.
					for (Video v : result) addon.setLiveVideoInfo(v.videoId, v.channel, v.durationMs);
				}
				rebuildSearchRows();
			});
		});
	}

	/** Favorites and Playlist entries whose title matches, read one list after the other (the tab's search panel does the same). */
	private void searchLibrary(String q, int gen) {
		MediaLib lib = a.getLib();
		String needle = q.toLowerCase(Locale.ROOT);
		List<BrowsableItem> sources = new ArrayList<>();
		sources.add(lib.getFavorites());
		lib.getPlaylists().getChildren().main().onCompletion((pls, err) -> {
			if (gen != generation) return;
			if (pls != null) {
				for (Item i : pls) {
					if (i instanceof BrowsableItem b) sources.add(b);
				}
			}
			collectLibrary(sources, 0, needle, new ArrayList<>(), new HashSet<>(), gen);
		});
	}

	private void collectLibrary(List<BrowsableItem> sources, int idx, String needle, List<PlayableItem> found,
															Set<String> seen, int gen) {
		if (gen != generation) return;
		if ((idx >= sources.size()) || (found.size() >= MAX_LIBRARY_RESULTS)) {
			libraryResults.clear();
			libraryResults.addAll(found);
			rebuildSearchRows();
			return;
		}
		sources.get(idx).getPlayableChildren(false).main().onCompletion((list, err) -> {
			if (list != null) {
				for (PlayableItem pi : list) {
					if (found.size() >= MAX_LIBRARY_RESULTS) break;
					String vid = videoIdOf(pi);
					if (!libraryTitle(pi, vid).toLowerCase(Locale.ROOT).contains(needle)) continue;
					if (seen.add((vid != null) ? vid : pi.getId())) found.add(pi);
				}
			}
			collectLibrary(sources, idx + 1, needle, found, seen, gen);
		});
	}

	private void onTyping(String text) {
		if (queueMode || text.equals(typing)) return;
		typing = text;
		suggestGeneration++;
		int gen = suggestGeneration;
		if (pendingSuggest != null) handler.removeCallbacks(pendingSuggest);
		if (text.isEmpty()) {
			fillChips("");
			return;
		}
		pendingSuggest = () -> suggestExecutor.execute(() -> {
			List<String> found = null;
			try {
				found = YoutubeSearch.suggest(text, MAX_CHIPS);
			} catch (Exception ex) {
				Log.d(ex, "YouTube suggestions failed");
			}
			List<String> s = found;
			handler.post(() -> {
				if ((gen == suggestGeneration) && (s != null)) fillChips(s);
			});
		});
		handler.postDelayed(pendingSuggest, SUGGEST_DELAY_MS);
	}

	/** Past searches while the field is empty. */
	private void fillChips(String text) {
		if (!text.isEmpty()) return;
		List<String> history = addon.getSearchHistory();
		fillChips(history.subList(0, Math.min(history.size(), MAX_CHIPS)));
	}

	private void fillChips(List<String> texts) {
		chips.removeAllViews();
		for (String t : texts) {
			TextView c = p.text(t, 13, p.primary, true);
			c.setSingleLine(true);
			c.setGravity(Gravity.CENTER);
			c.setPadding(p.dp(14), 0, p.dp(14), 0);
			c.setBackground(p.pressable(p.chipFill, 17));
			c.setClickable(true);
			c.setFocusable(true);
			c.setOnClickListener(v -> {
				input.setText(t);
				input.setSelection(t.length());
				search(t);
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, p.dp(34));
			lp.setMarginEnd(p.dp(6));
			chips.addView(c, lp);
		}
		chipScroll.setVisibility((queueMode || texts.isEmpty()) ? View.GONE : View.VISIBLE);
	}

	private void rebuildSearchRows() {
		rows.clear();
		if (!libraryResults.isEmpty()) {
			rows.add(Row.header(p.ctx.getString(R.string.youtube_search_library), null, null));
			for (PlayableItem pi : libraryResults) {
				String vid = videoIdOf(pi);
				rows.add(Row.library(K_LIBRARY, pi, vid, libraryTitle(pi, vid), p.ctx.getString(R.string.youtube_from_list, parentName(pi))));
			}
		}
		if (searched) {
			rows.add(Row.header(p.ctx.getString(R.string.youtube_search_results, query), null, null));
			if (searching) rows.add(Row.note(p.ctx.getString(R.string.yt_panel_searching)));
			else if (failed) rows.add(Row.note(p.ctx.getString(R.string.yt_panel_failed)));
			else if (results.isEmpty()) rows.add(Row.note(p.ctx.getString(R.string.yt_panel_no_results)));
			for (Video v : results) {
				String sub = (v.channel == null) ? "" : v.channel;
				if (v.durationText != null) sub = sub.isEmpty() ? v.durationText : (sub + "  ·  " + v.durationText);
				rows.add(Row.video(v.videoId, v.title, sub));
			}
		}
		adapter.notifyDataSetChanged();
	}

	// ---------------------------------------------------------------------------------------------
	// Up next: the queue, then what the playing Favorites / Playlist / Music queue goes on with

	private void loadQueueRows() {
		rows.clear();
		List<String> upNext = addon.getUpNext();
		String title = upNext.isEmpty() ? p.ctx.getString(R.string.youtube_up_next) :
				p.ctx.getString(R.string.youtube_up_next_count, upNext.size());
		rows.add(Row.header(title, upNext.isEmpty() ? null : p.ctx.getString(R.string.youtube_up_next_clear), addon::clearUpNext));
		for (String id : upNext) rows.add(Row.queued(id, addon.getDisplayTitle(id), p.ctx.getString(R.string.youtube_queued)));
		boolean hasList = (listName != null) && (listShuffled || !listItems.isEmpty());
		if (upNext.isEmpty() && !hasList) rows.add(Row.note(p.ctx.getString(R.string.youtube_up_next_empty)));
		if (hasList) {
			boolean queued = !upNext.isEmpty();
			int label = listShuffled ?
					(queued ? R.string.youtube_then_shuffled : R.string.youtube_playing_shuffled) :
					(queued ? R.string.youtube_then_from : R.string.youtube_playing_from);
			rows.add(Row.note(p.ctx.getString(label, listName)));
			for (PlayableItem pi : listItems) {
				String vid = videoIdOf(pi);
				rows.add(Row.library(K_LIST, pi, vid, libraryTitle(pi, vid), listName));
			}
		}
		adapter.notifyDataSetChanged();
	}

	private void onQueueChanged() {
		if (queueMode) loadQueueRows();
	}

	/**
	 * The next few entries of the Favorites / Playlist the playing video came from (or the Music queue, in its play order): what plays
	 * once Up next runs dry. With Shuffle on only the list's name is shown. How many is Settings > YouTube search & Up next.
	 */
	private void loadList() {
		int gen = ++listGeneration;
		PlayableItem q = addon.getQueueItem();
		BrowsableItem parent = (q != null) ? q.getParent() : null;
		int max = Math.max(1, Math.min(10, MainActivityPrefs.get().getIntPref(MainActivityPrefs.UP_NEXT_LIST_PREVIEW)));

		if (parent instanceof MusicQueue mq) {
			List<MusicTrackItem> order = mq.getPlayOrder();
			int idx = mq.indexInPlayOrder(q);
			boolean repeat = mq.getPrefs().getRepeatPref();
			listItems.clear();
			listName = mq.getName();
			listShuffled = false;
			if (idx != -1) {
				for (int i = 1; i <= max; i++) {
					int j = idx + i;
					if (j >= order.size()) {
						if (!repeat) break;
						j %= order.size();
					}
					if (j == idx) break;
					listItems.add(order.get(j));
				}
			}
			listLoaded();
			return;
		}
		if ((q == null) || (parent == null) || parent.isExternal()) {
			listItems.clear();
			listName = null;
			listLoaded();
			return;
		}
		boolean shuffle = parent.getPrefs().getShufflePref();
		boolean repeat = parent.getPrefs().getRepeatPref();
		String name = parent.getName();
		parent.getPlayableChildren(false).main().onCompletion((list, err) -> {
			if (gen != listGeneration) return;
			if (err != null) Log.d(err, "Failed to load the Up next list preview");
			listItems.clear();
			listName = name;
			listShuffled = shuffle;
			if ((list != null) && !shuffle) {
				int idx = -1;
				String id = q.getId();
				for (int i = 0; i < list.size(); i++) {
					if (id.equals(list.get(i).getId())) {
						idx = i;
						break;
					}
				}
				if (idx != -1) {
					for (int i = 1; i <= max; i++) {
						int j = idx + i;
						if (j >= list.size()) {
							if (!repeat) break;
							j %= list.size();
						}
						if (j == idx) break;
						listItems.add(list.get(j));
					}
				}
			}
			listLoaded();
		});
	}

	private void listLoaded() {
		if (queueMode) loadQueueRows();
	}

	// ---------------------------------------------------------------------------------------------
	// Doing things

	@Nullable
	private YoutubeFragment fragment() {
		if (a.getFragment(addon.getFragmentId()) instanceof YoutubeFragment f) return f;
		return (a.showFragment(addon.getFragmentId()) instanceof YoutubeFragment f) ? f : null;
	}

	private void play(Row r) {
		YoutubeFragment f = fragment();
		if (f == null) return;
		if (r.item != null) f.playFromList(r.item);
		else f.playVideoNow(r.id, r.title);
	}

	private boolean canPlayNext(Row r) {
		if (r.item == null) return true;
		return (r.item instanceof MusicTrackItem) || (r.id != null) || MusicPlayer.isMusicModeActive(a);
	}

	/** "Play next": into the music queue right behind the playing track when playing as music, else to the front of Up next. */
	private void playNext(Row r) {
		PlayableItem pi = r.item;
		if (pi instanceof MusicTrackItem t) {
			if (MusicPlayer.playNext(a, t)) toastNext(r.title);
			return;
		}
		if ((pi != null) && MusicPlayer.isMusicModeActive(a) && MusicPlayer.queueAfterCurrent(a, pi, true)) {
			toastNext(r.title);
			return;
		}
		if (r.id == null) return;
		YoutubeFragment f = fragment();
		if (f != null) f.queueVideo(r.id, r.title, true);
	}

	private void toastNext(String title) {
		me.aap.fermata.ui.view.TopToast.show(R.drawable.up_next, R.string.youtube_added_play_next, title);
	}

	private void hideKeyboard() {
		android.view.inputmethod.InputMethodManager im =
				(android.view.inputmethod.InputMethodManager) p.ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
		if ((im != null) && (input != null)) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
	}

	@Nullable
	private static String videoIdOf(PlayableItem pi) {
		if (pi instanceof MusicTrackItem t) return t.getVideoId();
		return YoutubeVideoItem.extractYoutubeVideoId(pi);
	}

	private String libraryTitle(PlayableItem pi, @Nullable String videoId) {
		String name = pi.getName();
		if ((videoId != null) && ((name == null) || name.isEmpty() || name.equals(videoId))) name = addon.getDisplayTitle(videoId);
		return (name == null) ? "" : name;
	}

	private static String parentName(PlayableItem pi) {
		BrowsableItem b = pi.getParent();
		return (b == null) ? "" : b.getName();
	}

	// ---------------------------------------------------------------------------------------------
	// The rows

	private static final class Row {
		final int type;
		final int kind;
		@Nullable
		final String id;
		final String title;
		final String sub;
		@Nullable
		final PlayableItem item;
		@Nullable
		final Runnable action;

		private Row(int type, int kind, @Nullable String id, String title, String sub, @Nullable PlayableItem item, @Nullable Runnable action) {
			this.type = type;
			this.kind = kind;
			this.id = id;
			this.title = (title == null) ? "" : title;
			this.sub = (sub == null) ? "" : sub;
			this.item = item;
			this.action = action;
		}

		static Row video(String id, String title, String sub) {
			return new Row(T_CARD, K_RESULT, id, title, sub, null, null);
		}

		static Row queued(String id, String title, String sub) {
			return new Row(T_CARD, K_QUEUED, id, title, sub, null, null);
		}

		static Row library(int kind, PlayableItem pi, @Nullable String id, String title, String sub) {
			return new Row(T_CARD, kind, id, title, sub, pi, null);
		}

		/** {@code label} is the little button at its end ("Clear"), {@code action} what it does. */
		static Row header(String title, @Nullable String label, @Nullable Runnable action) {
			return new Row(T_HEADER, 0, label, title, "", null, action);
		}

		static Row note(String text) {
			return new Row(T_NOTE, 0, null, text, "", null, null);
		}
	}

	private final class Holder extends RecyclerView.ViewHolder {
		ImageView thumb;
		TextView title;
		TextView sub;
		LinearLayout actions;
		TextView action;

		Holder(View v) {
			super(v);
		}
	}

	/** A round chip with just an icon. */
	private ImageView iconChip(int icon, boolean filled, int description, Runnable onClick) {
		ImageView v = new ImageView(p.ctx);
		v.setImageResource(icon);
		v.setImageTintList(ColorStateList.valueOf(filled ? p.onAccent : p.primary));
		v.setScaleType(ImageView.ScaleType.CENTER);
		v.setBackground(p.pressable(filled ? p.accent : p.chipFill, 18));
		v.setClickable(true);
		v.setFocusable(true);
		v.setContentDescription(p.ctx.getString(description));
		v.setOnClickListener(x -> onClick.run());
		// The card itself is what the steering wheel walks; these are for fingers.
		CarNav.skip(v);
		return v;
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {
		@Override
		public int getItemCount() {
			return rows.size();
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).type;
		}

		@Override
		public Holder onCreateViewHolder(ViewGroup parent, int type) {
			if (type == T_NOTE) {
				TextView t = p.text("", 14, p.secondary, false);
				t.setPadding(p.dp(8), p.dp(8), p.dp(8), p.dp(12));
				t.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
				Holder h = new Holder(t);
				h.title = t;
				return h;
			}
			if (type == T_HEADER) {
				LinearLayout row = new LinearLayout(p.ctx);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(Gravity.CENTER_VERTICAL);
				row.setPadding(p.dp(8), p.dp(6), p.dp(4), p.dp(8));
				row.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
				Holder h = new Holder(row);
				h.title = p.text("", 16, p.primary, true);
				row.addView(h.title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
				h.action = p.text("", 13, p.primary, true);
				h.action.setGravity(Gravity.CENTER);
				h.action.setPadding(p.dp(14), 0, p.dp(14), 0);
				h.action.setBackground(p.pressable(p.chipFill, 16));
				h.action.setClickable(true);
				h.action.setFocusable(true);
				CarNav.skip(h.action);
				row.addView(h.action, new LinearLayout.LayoutParams(WRAP_CONTENT, p.dp(32)));
				return h;
			}
			LinearLayout card = new LinearLayout(p.ctx);
			card.setOrientation(LinearLayout.HORIZONTAL);
			card.setGravity(Gravity.CENTER_VERTICAL);
			card.setPadding(p.dp(8), p.dp(8), p.dp(8), p.dp(8));
			card.setBackground(p.pressable(p.chipFill, 16));
			card.setClickable(true);
			card.setFocusable(true);
			RecyclerView.LayoutParams clp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			clp.bottomMargin = p.dp(8);
			card.setLayoutParams(clp);
			Holder h = new Holder(card);
			h.thumb = new ImageView(p.ctx);
			h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
			h.thumb.setBackgroundColor(0x22000000);
			h.thumb.setClipToOutline(true);
			h.thumb.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), p.dp(10));
				}
			});
			card.addView(h.thumb, new LinearLayout.LayoutParams(p.dp(112), p.dp(63)));
			LinearLayout texts = new LinearLayout(p.ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setPadding(p.dp(10), 0, p.dp(8), 0);
			h.title = p.text("", 14, p.primary, true);
			h.title.setMaxLines(2);
			h.title.setEllipsize(TextUtils.TruncateAt.END);
			h.sub = p.text("", 12, p.secondary, false);
			h.sub.setSingleLine(true);
			h.sub.setEllipsize(TextUtils.TruncateAt.END);
			texts.addView(h.title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			slp.topMargin = p.dp(4);
			texts.addView(h.sub, slp);
			card.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
			// The two chips, one above the other, at the card's end.
			h.actions = new LinearLayout(p.ctx);
			h.actions.setOrientation(LinearLayout.VERTICAL);
			h.actions.setGravity(Gravity.CENTER);
			card.addView(h.actions, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
			return h;
		}

		@Override
		public void onBindViewHolder(Holder h, int position) {
			Row r = rows.get(position);
			h.title.setText(r.title);
			if (r.type == T_NOTE) return;
			if (r.type == T_HEADER) {
				boolean has = (r.id != null) && (r.action != null);
				h.action.setVisibility(has ? View.VISIBLE : View.GONE);
				if (has) {
					h.action.setText(r.id);
					h.action.setOnClickListener(v -> r.action.run());
				}
				return;
			}
			h.sub.setText(r.sub);
			load(h.thumb, (r.id == null) ? null : "https://i.ytimg.com/vi/" + r.id + "/mqdefault.jpg");
			boolean library = (r.item != null);
			boolean queuesOnTap = tapQueues() && canPlayNext(r) && (r.kind != K_QUEUED);
			h.itemView.setOnClickListener(v -> {
				if (queuesOnTap) playNext(r);
				else play(r);
			});
			h.actions.removeAllViews();
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(p.dp(36), p.dp(36));
			h.actions.addView(iconChip(R.drawable.play, true, R.string.youtube_play_now, () -> play(r)), lp);
			LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(p.dp(36), p.dp(36));
			lp2.topMargin = p.dp(6);
			if (r.kind == K_QUEUED) {
				h.actions.addView(iconChip(R.drawable.playlist_remove, false, R.string.youtube_up_next_remove,
						() -> addon.removeUpNext(r.id)), lp2);
			} else if (canPlayNext(r)) {
				h.actions.addView(iconChip(R.drawable.playlist_add, false, R.string.youtube_play_next, () -> playNext(r)), lp2);
			}
		}
	}

	private void load(ImageView v, @Nullable String url) {
		v.setTag(url);
		if (url == null) {
			v.setImageDrawable(null);
			return;
		}
		Bitmap cached = images.get(url);
		if (cached != null) {
			v.setImageBitmap(cached);
			return;
		}
		v.setImageDrawable(null);
		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main().onSuccess(bm -> {
			if (bm == null) return;
			images.put(url, bm);
			if (url.equals(v.getTag())) v.setImageBitmap(bm);
		});
	}
}
