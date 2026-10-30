package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.Bitmap;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.action.CarNav;
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
	private static final long SUGGEST_DELAY_MS = 220;
	@Nullable
	private static YoutubePanel current;

	private final MainActivityDelegate a;
	private final YoutubeAddon addon;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final ExecutorService suggestExecutor = Executors.newSingleThreadExecutor();
	private final LruCache<String, Bitmap> images = new LruCache<>(60);
	private final List<Row> rows = new ArrayList<>();
	private final Runnable queueListener = () -> handler.post(this::onQueueChanged);
	private SidePanel p;
	private boolean queueMode;
	private boolean searching;
	private boolean failed;
	private boolean searched;
	private int generation;
	private int suggestGeneration;
	private String typing = "";
	private TextView pillSearch;
	private TextView pillQueue;
	private EditText input;
	private HorizontalScrollView chipScroll;
	private LinearLayout chips;
	private RecyclerView list;
	private TextView note;
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
			if (c.queueMode == queue) {
				SidePanel.dismissOpen();
			} else {
				c.setMode(queue);
			}
			return;
		}
		YoutubePanel panel = new YoutubePanel(a, addon, queue);
		current = panel;
		SidePanel.toggle(a, ID, queue ? R.drawable.up_next : R.drawable.search,
				queue ? R.string.action_youtube_up_next : R.string.action_youtube_search,
				MainActivityPrefs.SIDE_PANEL_YOUTUBE_PERCENT, panel);
	}

	// ---------------------------------------------------------------------------------------------

	@Override
	public View create(SidePanel panel) {
		this.p = panel;
		boolean car = a.isCarActivity();
		LinearLayout root = new LinearLayout(p.ctx);
		root.setOrientation(LinearLayout.VERTICAL);

		LinearLayout pills = new LinearLayout(p.ctx);
		pills.setOrientation(LinearLayout.HORIZONTAL);
		pills.setPadding(p.dp(4), p.dp(2), p.dp(4), p.dp(6));
		pillSearch = pill(p.ctx.getString(R.string.action_youtube_search), () -> setMode(false));
		pillQueue = pill(p.ctx.getString(R.string.youtube_up_next), () -> setMode(true));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, p.dp(38), 1f);
		plp.setMarginEnd(p.dp(8));
		pills.addView(pillSearch, plp);
		pills.addView(pillQueue, new LinearLayout.LayoutParams(0, p.dp(38), 1f));
		root.addView(pills, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

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
		ilp.setMargins(p.dp(4), 0, p.dp(4), p.dp(6));
		root.addView(input, ilp);

		chips = new LinearLayout(p.ctx);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setPadding(p.dp(4), 0, p.dp(4), p.dp(6));
		chipScroll = new HorizontalScrollView(p.ctx);
		chipScroll.setHorizontalScrollBarEnabled(false);
		chipScroll.addView(chips, new HorizontalScrollView.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		root.addView(chipScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		note = p.text("", 14, p.secondary, false);
		note.setGravity(Gravity.CENTER);
		note.setPadding(p.dp(16), p.dp(24), p.dp(16), p.dp(24));
		root.addView(note, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		list = new RecyclerView(p.ctx);
		list.setLayoutManager(new LinearLayoutManager(p.ctx));
		list.setAdapter(adapter);
		list.setClipToPadding(false);
		list.setPadding(0, 0, 0, p.dp(8));
		root.addView(list, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		addon.addUpNextListener(queueListener);
		setMode(queueMode);
		return root;
	}

	@Override
	public void onClosed() {
		addon.removeUpNextListener(queueListener);
		executor.shutdownNow();
		suggestExecutor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		if (current == this) current = null;
	}

	// ---------------------------------------------------------------------------------------------
	// Modes

	private void setMode(boolean queue) {
		queueMode = queue;
		pillSearch.setBackground(p.pressable(queue ? p.chipFill : p.accent, 19));
		pillSearch.setTextColor(queue ? p.primary : p.onAccent);
		pillQueue.setBackground(p.pressable(queue ? p.accent : p.chipFill, 19));
		pillQueue.setTextColor(queue ? p.onAccent : p.primary);
		input.setVisibility(queue ? View.GONE : View.VISIBLE);
		chipScroll.setVisibility(queue ? View.GONE : View.VISIBLE);
		if (queue) loadQueue();
		else showSearchState();
	}

	private void showSearchState() {
		fillChips(input.getText().toString().trim());
		rebuildSearchRows();
	}

	// ---------------------------------------------------------------------------------------------
	// Search

	private final List<Video> results = new ArrayList<>();

	private void search(String q) {
		if (q.isEmpty()) return;
		generation++;
		int gen = generation;
		searching = true;
		failed = false;
		searched = true;
		results.clear();
		addon.addSearchHistory(q);
		hideKeyboard();
		rebuildSearchRows();
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
		if (searching) {
			note.setText(p.ctx.getString(R.string.yt_panel_searching));
		} else if (failed) {
			note.setText(R.string.yt_panel_failed);
		} else if (searched && results.isEmpty()) {
			note.setText(R.string.yt_panel_no_results);
		} else {
			note.setText("");
		}
		for (Video v : results) {
			String sub = (v.channel == null) ? "" : v.channel;
			if (v.durationText != null) sub = sub.isEmpty() ? v.durationText : (sub + "  ·  " + v.durationText);
			rows.add(new Row(v.videoId, v.title, sub, false));
		}
		note.setVisibility(note.getText().length() == 0 ? View.GONE : View.VISIBLE);
		adapter.notifyDataSetChanged();
	}

	// ---------------------------------------------------------------------------------------------
	// Up next

	private void loadQueue() {
		rows.clear();
		for (String id : addon.getUpNext()) rows.add(new Row(id, addon.getDisplayTitle(id), p.ctx.getString(R.string.youtube_queued), true));
		note.setText(rows.isEmpty() ? p.ctx.getString(R.string.yt_panel_empty_queue) : "");
		note.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
		adapter.notifyDataSetChanged();
	}

	private void onQueueChanged() {
		if (queueMode) loadQueue();
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
		if (f != null) f.playVideoNow(r.id, r.title);
	}

	private void playNext(Row r) {
		YoutubeFragment f = fragment();
		if (f != null) f.queueVideo(r.id, r.title, true);
	}

	private void hideKeyboard() {
		android.view.inputmethod.InputMethodManager im =
				(android.view.inputmethod.InputMethodManager) p.ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
		if ((im != null) && (input != null)) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
	}

	// ---------------------------------------------------------------------------------------------
	// The cards

	private TextView pill(String label, Runnable onClick) {
		TextView t = p.text(label, 14, p.primary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> onClick.run());
		return t;
	}

	private TextView chip(String label, boolean filled, Runnable onClick) {
		TextView c = p.text(label, 12.5f, filled ? p.onAccent : p.primary, true);
		c.setSingleLine(true);
		c.setGravity(Gravity.CENTER);
		c.setPadding(p.dp(14), 0, p.dp(14), 0);
		c.setBackground(p.pressable(filled ? p.accent : p.chipFill, 16));
		c.setClickable(true);
		c.setFocusable(true);
		c.setOnClickListener(v -> onClick.run());
		// The card itself is what the steering wheel walks; these are for fingers.
		CarNav.skip(c);
		return c;
	}

	private static final class Row {
		final String id;
		final String title;
		final String sub;
		final boolean queued;

		Row(String id, String title, String sub, boolean queued) {
			this.id = id;
			this.title = (title == null) ? "" : title;
			this.sub = sub;
			this.queued = queued;
		}
	}

	private final class Holder extends RecyclerView.ViewHolder {
		final ImageView thumb;
		final TextView title;
		final TextView sub;
		final LinearLayout actions;

		Holder(LinearLayout card, ImageView thumb, TextView title, TextView sub, LinearLayout actions) {
			super(card);
			this.thumb = thumb;
			this.title = title;
			this.sub = sub;
			this.actions = actions;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {
		@Override
		public int getItemCount() {
			return rows.size();
		}

		@Override
		public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
			LinearLayout card = new LinearLayout(p.ctx);
			card.setOrientation(LinearLayout.VERTICAL);
			card.setPadding(p.dp(8), p.dp(8), p.dp(8), p.dp(8));
			card.setBackground(p.pressable(p.chipFill, 16));
			card.setClickable(true);
			card.setFocusable(true);
			RecyclerView.LayoutParams clp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			clp.bottomMargin = p.dp(8);
			card.setLayoutParams(clp);

			LinearLayout top = new LinearLayout(p.ctx);
			top.setOrientation(LinearLayout.HORIZONTAL);
			ImageView thumb = new ImageView(p.ctx);
			thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
			thumb.setBackgroundColor(0x22000000);
			thumb.setClipToOutline(true);
			thumb.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), p.dp(10));
				}
			});
			top.addView(thumb, new LinearLayout.LayoutParams(p.dp(124), p.dp(70)));
			LinearLayout texts = new LinearLayout(p.ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setPadding(p.dp(10), 0, 0, 0);
			TextView title = p.text("", 14, p.primary, true);
			title.setMaxLines(2);
			title.setEllipsize(TextUtils.TruncateAt.END);
			TextView sub = p.text("", 12, p.secondary, false);
			sub.setSingleLine(true);
			sub.setEllipsize(TextUtils.TruncateAt.END);
			texts.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			slp.topMargin = p.dp(4);
			texts.addView(sub, slp);
			top.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
			card.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

			LinearLayout actions = new LinearLayout(p.ctx);
			actions.setOrientation(LinearLayout.HORIZONTAL);
			LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH_PARENT, p.dp(34));
			alp.topMargin = p.dp(8);
			card.addView(actions, alp);
			return new Holder(card, thumb, title, sub, actions);
		}

		@Override
		public void onBindViewHolder(Holder h, int position) {
			Row r = rows.get(position);
			h.title.setText(r.title);
			h.sub.setText(r.sub);
			load(h.thumb, "https://i.ytimg.com/vi/" + r.id + "/mqdefault.jpg");
			h.itemView.setOnClickListener(v -> play(r));
			h.actions.removeAllViews();
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT);
			lp.setMarginEnd(p.dp(8));
			h.actions.addView(chip(p.ctx.getString(R.string.yt_panel_play), true, () -> play(r)), lp);
			if (r.queued) {
				h.actions.addView(chip(p.ctx.getString(R.string.youtube_up_next_remove), false, () -> addon.removeUpNext(r.id)), lp);
			} else {
				h.actions.addView(chip(p.ctx.getString(R.string.youtube_play_next), false, () -> playNext(r)), lp);
			}
		}
	}

	private void load(ImageView v, String url) {
		v.setTag(url);
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
