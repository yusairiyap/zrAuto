package me.aap.fermata.addon.web;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.ui.view.BehindBarsLayers;
import me.aap.utils.pref.PreferenceStore;

/**
 * The browser's built-in home page: big rounded cards of the user's bookmarks over a blurred
 * background that runs on behind the floating bars, like the Music tab's cover does.
 * <p>
 * A card is styled like a Favorites/Playlists grid card -- a full-bleed picture (here a screenshot
 * of the site, or a coloured placeholder until there is one), fading to black at the bottom under
 * a white title -- only rounder. Cards can be opened, edited, removed and moved around; while the
 * cards are being edited a long press picks one up to drag it to a new place, and the nav bar's
 * background layers stay put behind everything.
 * <p>
 * The background (backdrop colour, blurred picture, scrim) is made of layers that reach past this
 * view's edges, see {@link BehindBarsLayers}; {@link #setHomeVisible} fades them and the cards in
 * or out, so going to a site (or coming back) is one smooth motion.
 */
@SuppressLint("ViewConstructor")
final class BrowserHomeView extends FrameLayout implements PreferenceStore.Listener {
	/** The room the tab strip takes at the top, which the home page's content has to clear. */
	static final int TABS_BAR_HEIGHT_DP = 54;
	private static final int T_HEADER = 0;
	private static final int T_CARD = 1;
	private static final int T_ADD = 2;
	private static final int T_HEADER_COMPACT = 3;
	private static final int ANIM_MS = 260;

	interface Host {
		/** A card was tapped. */
		void open(BrowserBookmarks.Item item);

		/** The "+" card: ask for a new bookmark. */
		void add();

		/** Ask for a new name and address for this bookmark. */
		void edit(BrowserBookmarks.Item item);

		/** Remove this bookmark. */
		void remove(BrowserBookmarks.Item item);

		/** A card was long-pressed outside of edit mode. */
		void cardMenu(BrowserBookmarks.Item item, int index);
	}

	private final WebBrowserAddon addon;
	private final MainActivityDelegate activity;
	private final Host host;
	private final boolean light;
	private final boolean car;
	private final int textPrimary;
	private final int textSecondary;
	private final int chipFill;
	private final int chipRipple;
	private final View backdrop;
	private final ImageView bg;
	private final View scrim;
	private final RecyclerView list;
	private final GridLayoutManager layout;
	private final Adapter adapter = new Adapter();
	private final ItemTouchHelper touchHelper;
	private final BehindBarsLayers layers;
	private final List<BrowserBookmarks.Item> items = new ArrayList<>();
	private boolean editing;
	/**
	 * A short screen (the car's, a phone on its side): one row of big cards to swipe through instead
	 * of a grid, which would be cut off after a few cards. See applyMode().
	 */
	private boolean compact;
	/** The card size in compact mode: the room there is, top to bottom. */
	private int cardSide;
	private boolean homeShown = true;
	private float shown = 1f;
	@Nullable
	private ValueAnimator anim;
	@Nullable
	private Bitmap bgSource;
	private int bgGeneration;
	@Nullable
	private TextView editPill;
	@Nullable
	private TextView emptyNote;

	BrowserHomeView(Context ctx, WebBrowserAddon addon, MainActivityDelegate activity, Host host) {
		super(ctx);
		this.addon = addon;
		this.activity = activity;
		this.host = host;
		light = MusicPlayerFragment.isLightTheme(ctx);
		car = activity.isCarActivity();
		textPrimary = light ? 0xDE000000 : 0xFFFFFFFF;
		textSecondary = light ? 0x99000000 : 0xB3FFFFFF;
		chipFill = light ? 0x14000000 : 0x26FFFFFF;
		chipRipple = light ? 0x29000000 : 0x40FFFFFF;
		setClipChildren(false);
		setClipToPadding(false);

		// The layers reaching under the bars: same stack as the Music tab's.
		backdrop = new View(ctx);
		backdrop.setBackgroundColor(light ? 0xFFF3F4F7 : 0xFF101014);
		bg = new ImageView(ctx);
		bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
		bg.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		scrim = new View(ctx);
		scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
				light ? new int[]{0xB3FFFFFF, 0x8CFFFFFF, 0xF2FFFFFF} :
						new int[]{0x66000000, 0x8C000000, 0xE6000000}));
		addView(backdrop, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(bg, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		list = new RecyclerView(ctx);
		layout = new GridLayoutManager(ctx, 2);
		layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				int t = adapter.getItemViewType(position);
				return ((t == T_HEADER) || (t == T_HEADER_COMPACT)) ? layout.getSpanCount() : 1;
			}
		});
		list.setLayoutManager(layout);
		list.setAdapter(adapter);
		list.setHasFixedSize(false);
		list.setClipToPadding(false);
		list.setOverScrollMode(OVER_SCROLL_NEVER);
		int side = toIntPx(ctx, car ? 20 : 12);
		list.setPadding(side, 0, side, 0);
		list.addItemDecoration(new RecyclerView.ItemDecoration() {
			@Override
			public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View v,
																 @NonNull RecyclerView parent, @NonNull RecyclerView.State s) {
				int gap = toIntPx(getContext(), 6);
				int type = parent.getChildViewHolder(v).getItemViewType();
				if (compact) {
					// Clears the tab strip under the tool bar; one row, so the room left is the cards'.
					out.set(gap, toIntPx(getContext(), car ? 58 : 54), gap, gap);
				} else if (type == T_HEADER) {
					out.set(0, 0, 0, gap);
				} else {
					out.set(gap, gap, gap, gap);
				}
			}
		});
		addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		// Every scrolling screen reserves room for the floating bars itself, see
		// MainActivityDelegate#insetScrollableContent.
		activity.insetScrollableContent(list);

		list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateCardSide());
		touchHelper = new ItemTouchHelper(new DragCallback());
		touchHelper.attachToRecyclerView(list);

		layers = new BehindBarsLayers(activity, this, backdrop, bg, scrim);
		reload();
		loadBackground();
		applyBackgroundPrefs();
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		addon.getPreferenceStore().addBroadcastListener(this);
		layers.attach();
	}

	@Override
	protected void onDetachedFromWindow() {
		addon.getPreferenceStore().removeBroadcastListener(this);
		layers.detach();
		if (anim != null) anim.cancel();
		super.onDetachedFromWindow();
	}

	/** Called when the fragment's view goes away. */
	void release() {
		bgGeneration++;
		layers.detach();
		if (anim != null) anim.cancel();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		boolean c = h < toIntPx(getContext(), 500);
		if (c != compact) {
			compact = c;
			applyMode();
		}
		if (compact) return;
		int target = toIntPx(getContext(), car ? 250 : 176);
		int cols = Math.max(2, Math.min(6, w / Math.max(1, target)));
		if (layout.getSpanCount() != cols) layout.setSpanCount(cols);
	}

	private void applyMode() {
		layout.setOrientation(compact ? RecyclerView.HORIZONTAL : RecyclerView.VERTICAL);
		layout.setSpanCount(compact ? 1 : 2);
		adapter.notifyDataSetChanged();
		list.post(this::updateCardSide);
	}

	/** In compact mode the cards are as tall as the room under the tab strip allows. */
	private void updateCardSide() {
		if (!compact) return;
		int avail = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom() -
				dp(car ? 58 : 54) - dp(14);
		int side = Math.max(dp(110), avail);
		if (Math.abs(side - cardSide) > dp(3)) {
			cardSide = side;
			adapter.notifyDataSetChanged();
		}
	}

	// ---------------------------------------------------------------- data

	/** Re-reads the bookmarks. */
	void reload() {
		items.clear();
		items.addAll(BrowserBookmarks.list(addon));
		adapter.notifyDataSetChanged();
		if (emptyNote != null) emptyNote.setVisibility(items.isEmpty() ? VISIBLE : GONE);
	}

	boolean isEditing() {
		return editing;
	}

	void setEditing(boolean edit) {
		if (editing == edit) return;
		editing = edit;
		if (editPill != null) editPill.setText(editing ? R.string.browser_done : R.string.browser_edit_cards);
		adapter.notifyDataSetChanged();
	}

	/** Moves a bookmark card from one place to another and saves that order. */
	void move(int from, int to) {
		if ((from < 0) || (to < 0) || (from >= items.size()) || (to >= items.size()) || (from == to)) {
			return;
		}
		items.add(to, items.remove(from));
		saveOrder();
		// Positions shift by one for the header row.
		adapter.notifyItemMoved(from + 1, to + 1);
	}

	private void saveOrder() {
		Map<String, String> m = new LinkedHashMap<>();
		for (BrowserBookmarks.Item i : items) m.put(i.url, i.name);
		addon.setBookmarks(m);
	}

	// ---------------------------------------------------------------- show / hide

	boolean isHomeShown() {
		return homeShown;
	}

	/** Moves the cards vertically, following the tab strip growing out of the tool bar. */
	void setShift(float px) {
		list.setTranslationY(px);
	}

	/** Fades the home page (cards and background) in or out. */
	void setHomeVisible(boolean visible, boolean animate) {
		if (anim != null) anim.cancel();
		anim = null;
		homeShown = visible;

		if (visible) {
			setVisibility(VISIBLE);
			layers.setActive(true);
		}
		if (!animate) {
			applyShown(visible ? 1f : 0f);
			if (!visible) finishHide();
			return;
		}
		ValueAnimator a = ValueAnimator.ofFloat(shown, visible ? 1f : 0f);
		a.setDuration(ANIM_MS);
		a.setInterpolator(new DecelerateInterpolator());
		a.addUpdateListener(v -> applyShown((float) v.getAnimatedValue()));
		if (!visible) {
			a.addListener(new AnimatorListenerAdapter() {
				private boolean cancelled;

				@Override
				public void onAnimationCancel(Animator animation) {
					cancelled = true;
				}

				@Override
				public void onAnimationEnd(Animator animation) {
					if (!cancelled) finishHide();
				}
			});
		}
		anim = a;
		a.start();
	}

	private void finishHide() {
		setVisibility(GONE);
		layers.setActive(false);
	}

	private void applyShown(float f) {
		shown = f;
		layers.setShown(f);
		list.setAlpha(f);
		applyZoom();
	}

	// ---------------------------------------------------------------- background

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (prefs.contains(WebBrowserAddon.HOME_BG_SOURCE) ||
				prefs.contains(WebBrowserAddon.HOME_BG_IMAGE_URL)) {
			loadBackground();
		}
		if (prefs.contains(WebBrowserAddon.HOME_BG_ENABLED) ||
				prefs.contains(WebBrowserAddon.HOME_BG_BLUR)) {
			applyBackgroundPrefs();
		}
		if (prefs.contains(WebBrowserAddon.HOME_BG_ZOOM)) applyZoom();
	}

	private PreferenceStore prefs() {
		return addon.getPreferenceStore();
	}

	/** Picks the picture to blur -- see WebBrowserAddon.HOME_BG_SOURCE -- and shows it. */
	void loadBackground() {
		int gen = ++bgGeneration;
		int source = prefs().getIntPref(WebBrowserAddon.HOME_BG_SOURCE);

		if (source == WebBrowserAddon.HOME_BG_IMAGE) {
			String u = prefs().getStringPref(WebBrowserAddon.HOME_BG_IMAGE_URL);
			if ((u != null) && !u.trim().isEmpty()) {
				BrowserBookmarks.loadImage(BrowserBookmarks.normalizeUrl(u), b -> {
					if (gen == bgGeneration) setBackgroundSource(b);
				});
				return;
			}
		} else if (source == WebBrowserAddon.HOME_BG_FIRST_BOOKMARK) {
			List<BrowserBookmarks.Item> l = BrowserBookmarks.list(addon);
			if (!l.isEmpty()) {
				BrowserBookmarks.load(l.get(0).url, b -> {
					if (gen == bgGeneration) setBackgroundSource(b);
				});
				return;
			}
		} else {
			BrowserBookmarks.loadLast(b -> {
				if (gen == bgGeneration) setBackgroundSource(b);
			});
			return;
		}
		setBackgroundSource(null);
	}

	private void setBackgroundSource(@Nullable Bitmap b) {
		bgSource = (b != null) ? b : defaultBackground();
		applyBackgroundPrefs();
	}

	/** Shown until there is a picture of a visited site: soft colours to blur. */
	private Bitmap defaultBackground() {
		GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
				light ? new int[]{0xFFB8C6FF, 0xFFF0C6E8, 0xFFA8E6E0} :
						new int[]{0xFF3A3A6B, 0xFF6B2F62, 0xFF0F6B7A});
		Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(bm);
		g.setBounds(0, 0, 96, 96);
		g.draw(c);
		return bm;
	}

	/** Blur strength, and whether there's a background picture at all. */
	private void applyBackgroundPrefs() {
		boolean enabled = prefs().getBooleanPref(WebBrowserAddon.HOME_BG_ENABLED);
		bg.setVisibility(enabled ? VISIBLE : GONE);
		scrim.setVisibility(enabled ? VISIBLE : GONE);
		if (enabled && (bgSource != null)) {
			Bitmap blurred = blur(bgSource, prefs().getIntPref(WebBrowserAddon.HOME_BG_BLUR));
			bg.setImageDrawable((blurred != null) ? new BitmapDrawable(getResources(), blurred) : null);
		}
		applyZoom();
	}

	/** Zoom, plus the small settling zoom while fading in or out. */
	private void applyZoom() {
		float z = Math.max(100, Math.min(300, prefs().getIntPref(WebBrowserAddon.HOME_BG_ZOOM))) / 100f;
		float s = z * (1f + (1f - shown) * 0.06f);
		bg.setScaleX(s);
		bg.setScaleY(s);
	}

	/**
	 * A heavily blurred copy of {@code src}: downscaled to a few dozen pixels first, so the blur
	 * itself costs next to nothing, then stretched back up by the GPU's own bilinear filtering,
	 * the same way the Music tab does it.
	 */
	@Nullable
	private static Bitmap blur(Bitmap src, int strength) {
		try {
			if (strength <= 0) return src;
			if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
			if ((src == null) || (src.getWidth() <= 0) || (src.getHeight() <= 0)) return null;
			float f = Math.min(100, strength) / 100f;
			int w = Math.max(8, Math.round(256f * (float) Math.pow(16f / 256f, f)));
			int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
			int r = 1 + Math.round(2 * f);
			Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
			int[] px = new int[w * h];
			small.getPixels(px, 0, w, 0, 0, w, h);
			int[] tmp = new int[px.length];
			for (int pass = 0; pass < 3; pass++) {
				boxBlur(px, tmp, w, h, r, true);
				boxBlur(tmp, px, w, h, r, false);
			}
			return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
		} catch (Throwable ex) {
			return null;
		}
	}

	private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
		int len = horizontal ? w : h;
		int lines = horizontal ? h : w;

		for (int line = 0; line < lines; line++) {
			for (int i = 0; i < len; i++) {
				int rs = 0, gs = 0, bs = 0, n = 0;
				for (int k = -r; k <= r; k++) {
					int j = Math.min(len - 1, Math.max(0, i + k));
					int c = horizontal ? in[line * w + j] : in[j * w + line];
					rs += (c >> 16) & 0xFF;
					gs += (c >> 8) & 0xFF;
					bs += c & 0xFF;
					n++;
				}
				int idx = horizontal ? (line * w + i) : (i * w + line);
				out[idx] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
			}
		}
	}

	// ---------------------------------------------------------------- views

	private int dp(int v) {
		return toIntPx(getContext(), v);
	}

	private static Drawable roundRipple(int fill, int ripple, int radiusPx) {
		GradientDrawable content = new GradientDrawable();
		content.setColor(fill);
		content.setCornerRadius(radiusPx);
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(Color.BLACK);
		mask.setCornerRadius(radiusPx);
		return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
	}

	private TextView pill(int text) {
		TextView t = new TextView(getContext());
		t.setText(text);
		t.setTextColor(textPrimary);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 16 : 14);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setMinHeight(dp(car ? 52 : 40));
		t.setPadding(dp(18), dp(8), dp(18), dp(8));
		t.setBackground(roundRipple(chipFill, chipRipple, dp(40)));
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	/** The header in compact mode: a narrow column at the start of the row. */
	private View createCompactHeader() {
		Context ctx = getContext();
		LinearLayout col = new LinearLayout(ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_VERTICAL);
		col.setLayoutParams(new RecyclerView.LayoutParams(WRAP_CONTENT, MATCH_PARENT));
		col.setPadding(dp(8), 0, dp(14), 0);
		TextView title = new TextView(ctx);
		title.setText(me.aap.fermata.R.string.bookmarks);
		title.setTextColor(textPrimary);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 26 : 22);
		col.addView(title);
		TextView edit = pill(editing ? R.string.browser_done : R.string.browser_edit_cards);
		edit.setOnClickListener(v -> setEditing(!editing));
		editPill = edit;
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		col.addView(edit, lp);
		TextView note = new TextView(ctx);
		note.setText(R.string.browser_home_empty);
		note.setTextColor(textSecondary);
		note.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 16 : 13);
		note.setMaxWidth(dp(200));
		note.setPadding(0, dp(8), 0, 0);
		note.setVisibility(items.isEmpty() ? VISIBLE : GONE);
		emptyNote = note;
		col.addView(note);
		return col;
	}

	private View createHeader() {
		Context ctx = getContext();
		LinearLayout col = new LinearLayout(ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		// Room for the tab strip, which floats over the top of this page.
		col.setPadding(dp(8), dp(car ? 58 : TABS_BAR_HEIGHT_DP), dp(8), dp(4));

		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = new TextView(ctx);
		title.setText(me.aap.fermata.R.string.bookmarks);
		title.setTextColor(textPrimary);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 32 : 26);
		row.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		TextView edit = pill(editing ? R.string.browser_done : R.string.browser_edit_cards);
		edit.setOnClickListener(v -> setEditing(!editing));
		editPill = edit;
		row.addView(edit, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		col.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView note = new TextView(ctx);
		note.setText(R.string.browser_home_empty);
		note.setTextColor(textSecondary);
		note.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 18 : 15);
		note.setPadding(0, dp(10), 0, 0);
		note.setVisibility(items.isEmpty() ? VISIBLE : GONE);
		emptyNote = note;
		col.addView(note, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return col;
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Override
		public int getItemCount() {
			return items.size() + 2;
		}

		@Override
		public int getItemViewType(int position) {
			if (position == 0) return compact ? T_HEADER_COMPACT : T_HEADER;
			return (position == items.size() + 1) ? T_ADD : T_CARD;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			View v;
			if (type == T_HEADER) v = createHeader();
			else if (type == T_HEADER_COMPACT) v = createCompactHeader();
			else if (type == T_ADD) v = new AddCard(parent.getContext());
			else v = new Card(parent.getContext());
			if ((type != T_HEADER) && (type != T_HEADER_COMPACT)) {
				v.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			}
			return new RecyclerView.ViewHolder(v) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			int type = getItemViewType(position);
			if ((type == T_CARD) || (type == T_ADD)) {
				RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) h.itemView.getLayoutParams();
				int w = (compact && (cardSide > 0)) ? cardSide : MATCH_PARENT;
				int ht = (compact && (cardSide > 0)) ? cardSide : WRAP_CONTENT;
				if ((lp.width != w) || (lp.height != ht)) {
					lp.width = w;
					lp.height = ht;
					h.itemView.setLayoutParams(lp);
				}
			}
			if (type == T_CARD) ((Card) h.itemView).bind(items.get(position - 1), position - 1);
			else if (type == T_ADD) h.itemView.setVisibility(editing ? GONE : VISIBLE);
		}

		@Override
		public void onViewRecycled(@NonNull RecyclerView.ViewHolder h) {
			if (h.itemView instanceof Card c) c.stopWiggle();
		}
	}

	/** One bookmark: a rounded, square, full-bleed picture with its title over a dark fade. */
	private final class Card extends FrameLayout {
		private final ImageView image;
		private final TextView letter;
		private final TextView title;
		private final TextView sub;
		private final ImageView remove;
		private final ImageView editBadge;
		@Nullable
		private ObjectAnimator wiggle;
		@Nullable
		private BrowserBookmarks.Item item;

		Card(Context ctx) {
			super(ctx);
			final int radius = dp(24);
			setClipToOutline(true);
			setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
				}
			});
			setElevation(dp(6));
			setFocusable(true);
			setClickable(true);
			setOnFocusChangeListener((v, focus) -> v.animate().scaleX(focus ? 1.05f : 1f)
					.scaleY(focus ? 1.05f : 1f).setDuration(120).start());

			image = new ImageView(ctx);
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			addView(image, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

			letter = new TextView(ctx);
			letter.setTextColor(0x99FFFFFF);
			letter.setTypeface(Typeface.DEFAULT_BOLD);
			letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 76 : 64);
			letter.setGravity(Gravity.CENTER);
			addView(letter, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

			// Same fade as a Favorites/Playlists grid card: strongest at the bottom, under the title.
			View fade = new View(ctx);
			fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
					new int[]{0xF2000000, 0x66000000, 0x00000000}));
			addView(fade, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setPadding(dp(14), 0, dp(14), dp(14));
			LayoutParams tlp = new LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM);
			addView(texts, tlp);

			title = new TextView(ctx);
			title.setTextColor(Color.WHITE);
			title.setTypeface(Typeface.DEFAULT_BOLD);
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 20 : 17);
			title.setMaxLines(2);
			title.setEllipsize(android.text.TextUtils.TruncateAt.END);
			title.setShadowLayer(3f, 0f, 1f, 0xB0000000);
			texts.addView(title);

			sub = new TextView(ctx);
			sub.setTextColor(0xCCFFFFFF);
			sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 15 : 12);
			sub.setSingleLine(true);
			sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
			sub.setShadowLayer(3f, 0f, 1f, 0xB0000000);
			texts.addView(sub);

			int badge = dp(car ? 44 : 36);
			int m = dp(8);
			remove = badge(ctx, me.aap.utils.R.drawable.close);
			LayoutParams rlp = new LayoutParams(badge, badge, Gravity.TOP | Gravity.END);
			rlp.setMargins(m, m, m, m);
			addView(remove, rlp);
			editBadge = badge(ctx, me.aap.fermata.R.drawable.edit);
			LayoutParams elp = new LayoutParams(badge, badge, Gravity.TOP | Gravity.START);
			elp.setMargins(m, m, m, m);
			addView(editBadge, elp);

			setForeground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null,
					new ColorDrawable(Color.BLACK)));
		}

		private ImageView badge(Context ctx, int icon) {
			ImageView v = new ImageView(ctx);
			GradientDrawable d = new GradientDrawable();
			d.setShape(GradientDrawable.OVAL);
			d.setColor(0x99000000);
			v.setBackground(d);
			v.setImageResource(icon);
			v.setColorFilter(Color.WHITE);
			int p = dp(8);
			v.setPadding(p, p, p, p);
			v.setVisibility(GONE);
			return v;
		}

		@Override
		protected void onMeasure(int w, int h) {
			// Square.
			int size = MeasureSpec.getSize(w);
			super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
		}

		void bind(BrowserBookmarks.Item i, int index) {
			item = i;
			title.setText(i.name.isEmpty() ? i.host() : i.name);
			sub.setText(i.host());
			setContentDescription(title.getText());
			setTag(i.url);

			int hue = Math.abs(i.host().hashCode()) % 360;
			image.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
					new int[]{Color.HSVToColor(new float[]{hue, 0.55f, 0.62f}),
							Color.HSVToColor(new float[]{(hue + 40) % 360, 0.70f, 0.30f})}));
			String h = i.host();
			letter.setText(h.isEmpty() ? "?" : h.substring(0, 1).toUpperCase());
			letter.setVisibility(VISIBLE);

			Bitmap b = BrowserBookmarks.cached(i.url);
			if (b != null) {
				showThumb(b);
			} else {
				String url = i.url;
				BrowserBookmarks.load(url, bm -> {
					if ((bm != null) && url.equals(getTag())) showThumb(bm);
				});
			}

			remove.setVisibility(editing ? VISIBLE : GONE);
			editBadge.setVisibility(editing ? VISIBLE : GONE);
			remove.setOnClickListener(v -> host.remove(i));
			setOnClickListener(v -> {
				if (editing) host.edit(i);
				else host.open(i);
			});
			if (editing) {
				setOnLongClickListener(null);
				startWiggle(index);
			} else {
				setOnLongClickListener(v -> {
					host.cardMenu(i, index);
					return true;
				});
				stopWiggle();
			}
		}

		private void showThumb(Bitmap b) {
			image.setImageBitmap(b);
			letter.setVisibility(GONE);
		}

		private void startWiggle(int index) {
			if (wiggle != null) return;
			// Each card a little out of step with its neighbours.
			wiggle = ObjectAnimator.ofFloat(this, ROTATION, -1.1f, 1.1f);
			wiggle.setDuration(130 + (index % 4) * 17L);
			wiggle.setRepeatMode(ValueAnimator.REVERSE);
			wiggle.setRepeatCount(ValueAnimator.INFINITE);
			wiggle.start();
		}

		void stopWiggle() {
			if (wiggle != null) {
				wiggle.cancel();
				wiggle = null;
			}
			setRotation(0f);
		}
	}

	/** The last card: a "+" to add a bookmark. */
	private final class AddCard extends FrameLayout {
		AddCard(Context ctx) {
			super(ctx);
			int radius = dp(24);
			setBackground(roundRipple(chipFill, chipRipple, radius));
			setClickable(true);
			setFocusable(true);
			TextView plus = new TextView(ctx);
			plus.setText("+");
			plus.setTextColor(textPrimary);
			plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 64 : 52);
			plus.setGravity(Gravity.CENTER);
			addView(plus, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
			TextView label = new TextView(ctx);
			label.setText(R.string.browser_add_bookmark);
			label.setTextColor(textSecondary);
			label.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 16 : 13);
			label.setGravity(Gravity.CENTER);
			label.setPadding(dp(8), 0, dp(8), dp(14));
			addView(label, new LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM));
			setOnClickListener(v -> host.add());
		}

		@Override
		protected void onMeasure(int w, int h) {
			int size = MeasureSpec.getSize(w);
			super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
		}
	}

	/** Drag-to-reorder, only while the cards are being edited. */
	private final class DragCallback extends ItemTouchHelper.Callback {
		private int from = -1;
		private int to = -1;

		@Override
		public boolean isLongPressDragEnabled() {
			return editing;
		}

		@Override
		public boolean isItemViewSwipeEnabled() {
			return false;
		}

		@Override
		public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
			if (!editing || (vh.getItemViewType() != T_CARD)) return 0;
			return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT |
					ItemTouchHelper.RIGHT, 0);
		}

		@Override
		public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
													@NonNull RecyclerView.ViewHolder target) {
			if (target.getItemViewType() != T_CARD) return false;
			int f = vh.getBindingAdapterPosition() - 1;
			int t = target.getBindingAdapterPosition() - 1;
			if ((f < 0) || (t < 0) || (f >= items.size()) || (t >= items.size())) return false;
			if (from == -1) from = f;
			to = t;
			items.add(t, items.remove(f));
			adapter.notifyItemMoved(f + 1, t + 1);
			return true;
		}

		@Override
		public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
		}

		@Override
		public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int state) {
			super.onSelectedChanged(vh, state);
			if ((vh != null) && (state == ItemTouchHelper.ACTION_STATE_DRAG)) {
				vh.itemView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
			}
		}

		@Override
		public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
			super.clearView(rv, vh);
			vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
			if ((from != -1) && (from != to)) saveOrder();
			from = -1;
			to = -1;
		}
	}
}
