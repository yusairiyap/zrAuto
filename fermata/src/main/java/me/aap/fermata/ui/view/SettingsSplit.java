package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.fragment.SettingsFragment;
import me.aap.utils.log.Log;

/**
 * On a wide window (a tablet) every settings page (Settings itself, Home settings, the key simulator, the diagnostic log, ...)
 * sits beside the same list of settings categories, as in One UI's settings: the list is one view of the body that stays while
 * the pages change, so going from a page to another category never redraws it, and a divider between the two can be dragged to
 * make it wider or narrower (the width is remembered, {@link MainActivityPrefs#SETTINGS_NAV_DP}). The pages shift right to make
 * room for it; nothing in them needs to know.
 */
public final class SettingsSplit {
	private static final int MIN_DP = 220;
	private static final int MAX_DP = 520;
	/** One per body layout (the phone's and the car screen's). */
	private static final java.util.List<WeakReference<SettingsSplit>> live = new java.util.ArrayList<>();

	private final ConstraintLayout body;
	@Nullable
	private FrameLayout panel;
	@Nullable
	private RecyclerView list;
	@Nullable
	private NavAdapter adapter;
	private int widthPx;
	private boolean shown;
	private boolean preparing;
	@Nullable
	private ValueAnimator anim;
	private SettingsStyle.Colors colors;

	public SettingsSplit(@NonNull ConstraintLayout body) {
		this.body = body;
		live.add(new WeakReference<>(this));
	}

	/** The list of categories changed (Settings was built): shown, or taken down, as it should be. */
	static void entriesChanged() {
		for (WeakReference<SettingsSplit> w : new java.util.ArrayList<>(live)) {
			SettingsSplit s = w.get();
			if (s == null) {
				live.remove(w);
				continue;
			}
			// The body of an activity that was destroyed (the theme changed, the system recreated it) is not asked: its delegate is gone
			// and asking for it throws.
			if (!s.body.isAttachedToWindow()) {
				live.remove(w);
				continue;
			}
			try {
				MainActivityDelegate a = MainActivityDelegate.get(s.body.getContext());
				s.body.post(() -> s.sync(a));
			} catch (RuntimeException ex) {
				live.remove(w);
			}
		}
	}

	static void selectionChanged() {
		for (WeakReference<SettingsSplit> w : live) {
			SettingsSplit s = w.get();
			if ((s != null) && (s.adapter != null)) s.adapter.notifyDataSetChanged();
		}
	}

	/** Whether the window is wide enough for the two panes (the car screen never gets them). */
	public static boolean isWide(@NonNull MainActivityDelegate a) {
		return !a.isCarActivity() && (a.getContext().getResources().getConfiguration().screenWidthDp >= 720);
	}

	/** The tab in front changed (or the window did): the list comes or goes. */
	public void sync(@NonNull MainActivityDelegate a) {
		boolean page = isWide(a) && !a.isVideoMode() && (a.getActiveFragment() instanceof MainActivityFragment f) && f.isSettingsPage();
		// A settings page opened before Settings ever was (Home's chips): the list is made now, not at every start of the app.
		if (page && SettingsNav.entries().isEmpty() && !preparing) {
			preparing = true;
			body.post(() -> {
				try {
					SettingsFragment.prepareNav(a);
				} finally {
					preparing = false;
				}
			});
		}
		boolean want = page && !SettingsNav.entries().isEmpty() &&
				(a.getActiveFragment() instanceof MainActivityFragment f) && f.isSettingsPage();
		if (want && (a.getActiveFragment() instanceof MainActivityFragment f) && (f.settingsEntryTitle() != 0)) {
			// The entry the page belongs to: a category (Network belongs to Playback) or a button, found by its title.
			String title = a.getContext().getString(f.settingsEntryTitle());
			String key = "btn:" + title;
			for (SettingsNav.Entry e : SettingsNav.entries()) {
				if (title.contentEquals(e.title)) {
					key = e.key;
					break;
				}
			}
			SettingsNav.select(key);
		}
		if (want) show(a);
		else hide();
	}

	private void show(MainActivityDelegate a) {
		if (shown) {
			if (adapter != null) adapter.notifyDataSetChanged();
			return;
		}
		shown = true;
		Context ctx = body.getContext();
		colors = new SettingsStyle.Colors(ctx);
		widthPx = clamp(colors.dp(a.getPrefs().getIntPref(MainActivityPrefs.SETTINGS_NAV_DP)));
		if (panel == null) build(a);
		panel.setVisibility(View.VISIBLE);
		adapter.notifyDataSetChanged();
		ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) panel.getLayoutParams();
		lp.width = widthPx;
		panel.setLayoutParams(lp);
		panel.bringToFront();
		animate(0, widthPx, true);
	}

	private void hide() {
		if (!shown || (panel == null)) return;
		shown = false;
		animate(widthPx, 0, false);
	}

	private void build(MainActivityDelegate a) {
		Context ctx = body.getContext();
		FrameLayout p = new FrameLayout(ctx);
		RecyclerView rv = new RecyclerView(ctx);
		rv.setLayoutManager(new LinearLayoutManager(ctx));
		rv.setClipToPadding(false);
		rv.setPadding(colors.dp(12), colors.dp(8), colors.dp(26), colors.dp(16));
		adapter = new NavAdapter(a);
		rv.setAdapter(adapter);
		rv.setItemAnimator(null);
		p.addView(rv, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		Divider d = new Divider(ctx, a);
		p.addView(d, new FrameLayout.LayoutParams(colors.dp(26), MATCH_PARENT, Gravity.END));
		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(widthPx, 0);
		lp.topToTop = R.id.swiperefresh;
		lp.bottomToBottom = R.id.swiperefresh;
		lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		body.addView(p, lp);
		panel = p;
		list = rv;
		a.insetScrollableContent(rv);
	}

	private int clamp(int px) {
		float d = colors.density;
		int max = Math.min(Math.round(MAX_DP * d), (int) (Math.max(1, body.getWidth()) * 0.5f));
		return Math.max(Math.round(MIN_DP * d), Math.min(Math.max(max, Math.round(MIN_DP * d)), px));
	}

	/** The list slides in or out while the pages make room for it (or take the room back). */
	private void animate(int from, int to, boolean in) {
		if (anim != null) anim.cancel();
		View pr = body.findViewById(R.id.swiperefresh);
		FrameLayout p = panel;
		if ((pr == null) || (p == null)) return;
		p.animate().cancel();
		ValueAnimator va = ValueAnimator.ofInt(from, to);
		va.setDuration(240);
		va.setInterpolator(new DecelerateInterpolator(1.6f));
		va.addUpdateListener(v -> {
			int w = (Integer) v.getAnimatedValue();
			setContentMargin(pr, w);
			float f = (widthPx == 0) ? 1f : Math.max(0f, Math.min(1f, w / (float) widthPx));
			p.setTranslationX(-(1f - f) * widthPx * 0.35f);
			p.setAlpha(f);
		});
		va.addListener(new android.animation.AnimatorListenerAdapter() {
			private boolean cancelled;

			@Override
			public void onAnimationCancel(android.animation.Animator animation) {
				cancelled = true;
			}

			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (cancelled || in) return;
				p.setVisibility(View.GONE);
				setContentMargin(pr, 0);
			}
		});
		anim = va;
		va.start();
	}

	private static void setContentMargin(View content, int px) {
		ViewGroup.LayoutParams lp = content.getLayoutParams();
		if (lp instanceof ViewGroup.MarginLayoutParams m) {
			if (m.getMarginStart() == px) return;
			m.setMarginStart(px);
			content.setLayoutParams(m);
		}
	}

	/** The divider being dragged: the list and the pages follow the finger. */
	private void resize(int px) {
		int w = clamp(px);
		if ((w == widthPx) || (panel == null)) return;
		widthPx = w;
		if (anim != null) anim.cancel();
		ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) panel.getLayoutParams();
		lp.width = w;
		panel.setLayoutParams(lp);
		panel.setTranslationX(0);
		panel.setAlpha(1f);
		View pr = body.findViewById(R.id.swiperefresh);
		if (pr != null) setContentMargin(pr, w);
	}

	// ---------------------------------------------------------------------------------------------

	/** The thin line between the list and the page, with a grip in its middle; dragging it sideways resizes the list. */
	@SuppressLint("ViewConstructor")
	private final class Divider extends View {
		private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF r = new RectF();
		private final MainActivityDelegate a;
		private float downX;
		private int downW;
		private boolean dragging;

		Divider(Context ctx, MainActivityDelegate a) {
			super(ctx);
			this.a = a;
			line.setColor(colors.textSecondary);
			line.setAlpha(46);
			grip.setColor(colors.textSecondary);
			grip.setAlpha(150);
			setContentDescription(ctx.getString(R.string.settings_split_resize));
		}

		@Override
		protected void onDraw(@NonNull Canvas c) {
			float x = getWidth() - colors.dp(11);
			float h = colors.dp(dragging ? 56 : 44);
			float w = colors.dp(dragging ? 6 : 4);
			r.set(x - w / 2f, (getHeight() - h) / 2f, x + w / 2f, (getHeight() + h) / 2f);
			c.drawRoundRect(r, w / 2f, w / 2f, grip);
		}

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public boolean onTouchEvent(MotionEvent e) {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					downX = e.getRawX();
					downW = widthPx;
					dragging = true;
					if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_MOVE -> {
					if (dragging) resize(downW + Math.round(e.getRawX() - downX));
					return true;
				}
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					if (dragging) {
						dragging = false;
						a.getPrefs().applyIntPref(MainActivityPrefs.SETTINGS_NAV_DP, Math.round(widthPx / colors.density));
						invalidate();
					}
					return true;
				}
			}
			return super.onTouchEvent(e);
		}
	}

	private final class NavAdapter extends RecyclerView.Adapter<NavAdapter.Holder> {
		private final MainActivityDelegate a;

		NavAdapter(MainActivityDelegate a) {
			this.a = a;
		}

		final class Holder extends RecyclerView.ViewHolder {
			final ImageView icon;
			final TextView title;
			final TextView sub;

			Holder(LinearLayout row, ImageView icon, TextView title, TextView sub) {
				super(row);
				this.icon = icon;
				this.title = title;
				this.sub = sub;
			}
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			Context ctx = parent.getContext();
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(colors.dp(14), colors.dp(11), colors.dp(12), colors.dp(11));
			row.setClickable(true);
			row.setFocusable(true);
			ImageView icon = new ImageView(ctx);
			icon.setImageTintList(ColorStateList.valueOf(colors.accent));
			row.addView(icon, new LinearLayout.LayoutParams(colors.dp(22), colors.dp(22)));
			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setPadding(colors.dp(14), 0, 0, 0);
			TextView title = new TextView(ctx);
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
			title.setTypeface(Typeface.DEFAULT_BOLD);
			title.setTextColor(colors.textPrimary);
			title.setSingleLine(true);
			title.setEllipsize(android.text.TextUtils.TruncateAt.END);
			texts.addView(title);
			TextView sub = new TextView(ctx);
			sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
			sub.setTextColor(colors.textSecondary);
			sub.setMaxLines(2);
			sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
			texts.addView(sub);
			row.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
			RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.topMargin = colors.dp(4);
			lp.bottomMargin = colors.dp(4);
			row.setLayoutParams(lp);
			return new Holder(row, icon, title, sub);
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			List<SettingsNav.Entry> es = SettingsNav.entries();
			if (position >= es.size()) return;
			SettingsNav.Entry e = es.get(position);
			h.title.setText(e.title);
			h.sub.setText(e.subtitle);
			h.sub.setVisibility((e.subtitle == null) || (e.subtitle.length() == 0) ? View.GONE : View.VISIBLE);
			if (e.icon != 0) {
				h.icon.setVisibility(View.VISIBLE);
				h.icon.setImageResource(e.icon);
			} else {
				h.icon.setVisibility(View.INVISIBLE);
			}
			boolean on = e.key.equals(SettingsNav.selected());
			// Always a stroke of the same width (clear when not picked), so a picked row is not a different size;
			// the glass UI style's own tile when it is on (SettingsStyle.Colors#tile).
			// The same row again (another category picked): its tile glides to picked or not; a row bound to another
			// entry starts as it should be.
			Object tile = h.itemView.getTag(R.id.glass_tile);
			if (Glass.isTile((android.graphics.drawable.Drawable) ((tile instanceof android.graphics.drawable.Drawable) ? tile : null)) &&
					e.key.equals(h.itemView.getTag(R.id.glass_tile_key))) {
				Glass.setPicked((android.graphics.drawable.Drawable) tile, on, h.itemView.isAttachedToWindow());
			} else {
				GradientDrawable fill = colors.tile(16, on);
				h.itemView.setTag(R.id.glass_tile, fill);
				h.itemView.setTag(R.id.glass_tile_key, e.key);
				h.itemView.setBackground(colors.pressable(fill));
			}
			h.itemView.setOnClickListener(v -> click(a, e));
		}

		@Override
		public int getItemCount() {
			return SettingsNav.entries().size();
		}
	}

	private static int mix(int base, int over, float f) {
		int r = Math.round(((base >> 16) & 0xFF) * (1f - f) + ((over >> 16) & 0xFF) * f);
		int g = Math.round(((base >> 8) & 0xFF) * (1f - f) + ((over >> 8) & 0xFF) * f);
		int b = Math.round((base & 0xFF) * (1f - f) + (over & 0xFF) * f);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static void click(MainActivityDelegate a, SettingsNav.Entry e) {
		SettingsNav.select(e.key);
		if (e.setId != 0) {
			if (a.getActiveFragment() instanceof SettingsFragment s) s.showCategory(e.setId);
			else a.showFragment(R.id.settings_fragment, SettingsFragment.category(e.setId));
		} else if (e.action != null) {
			try {
				e.action.run();
			} catch (RuntimeException ex) {
				Log.e(ex, "A settings entry failed");
			}
		}
	}
}
