package me.aap.utils.ui.view;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.LEFT;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.RIGHT;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
import static me.aap.utils.ui.UiUtils.getTextAppearanceSize;
import static me.aap.utils.ui.UiUtils.isVisible;
import static me.aap.utils.ui.UiUtils.toIntPx;
import static me.aap.utils.ui.UiUtils.toPx;
import static me.aap.utils.ui.fragment.ViewFragmentMediator.attachMediator;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DimenRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import me.aap.utils.R;
import me.aap.utils.event.EventBroadcaster;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.ActivityListener;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.fragment.ViewFragmentMediator;

/**
 * @author Andrey Pavlenko
 */
public class ToolBarView extends ConstraintLayout implements ActivityListener,
		EventBroadcaster<ToolBarView.Listener> {
	@DimenRes
	private final int size;
	@StyleRes
	private final int textAppearance;
	@StyleRes
	private final int editTextAppearance;
	private Mediator mediator;
	private final Collection<ListenerRef<Listener>> listeners = new LinkedList<>();

	public ToolBarView(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, androidx.appcompat.R.attr.toolbarStyle);
	}

	public ToolBarView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.ToolBarView,
				androidx.appcompat.R.attr.toolbarStyle, R.style.Theme_Utils_Base_ToolBarStyle);
		size = ta.getLayoutDimension(R.styleable.ToolBarView_size, 0);
		textAppearance = ta.getResourceId(R.styleable.ToolBarView_textAppearance, 0);
		editTextAppearance = ta.getResourceId(R.styleable.ToolBarView_editTextAppearance, 0);
		setBackgroundColor(ta.getColor(R.styleable.ToolBarView_android_colorBackground, Color.TRANSPARENT));
		ta.recycle();

		ActivityDelegate a = getActivity();
		a.addBroadcastListener(this, Mediator.DEFAULT_EVENT_MASK);
		setMediator(a.getActiveFragment());
	}

	// No LayoutTransition for buttons coming and going: mediators animate their own changes where
	// they want to (e.g. YouTube's search field, with TransitionManager), and a second, generic
	// animation on top of those ran twice and could leave the bar unresponsive mid-way.
	private static final long ANIM_DURATION = 180L;

	/** A new tab's title and buttons fade in, rising a little into place. */
	private void animateContentIn() {
		if (!isLaidOut() || !isAttachedToWindow()) return;
		float dy = toPx(getContext(), 6);
		for (int i = 0, n = getChildCount(); i < n; i++) {
			View v = getChildAt(i);
			float alpha = v.getAlpha();
			if (alpha <= 0f) continue;
			v.animate().cancel();
			v.setAlpha(0f);
			v.setTranslationY(dy);
			v.animate().alpha(alpha).translationY(0f).setDuration(ANIM_DURATION)
					.setStartDelay(i * 15L).setInterpolator(new DecelerateInterpolator()).start();
		}
	}

	// The height the owner wants at least (e.g. the nav bar's thickness), 0 for none, and the
	// vertical padding that keeps the buttons their own size inside it -- see setMinBarHeight().
	private int minBarHeight;
	private float sizeScale = 1F;
	@Nullable
	private ColorStateList iconTint;

	/**
	 * Makes the bar at least {@code height} tall (still taller if its own size setting says so),
	 * padded by {@code vPad} at the top and bottom so the buttons keep the size the padding leaves
	 * them rather than growing with the bar.
	 */
	public void setMinBarHeight(int height, int vPad) {
		if ((height == minBarHeight) && (getPaddingTop() == vPad) && (getPaddingBottom() == vPad)) {
			return;
		}
		minBarHeight = height;
		setPadding(getPaddingLeft(), vPad, getPaddingRight(), vPad);
		applyHeight((int) (size * sizeScale));
	}

	private void applyHeight(int own) {
		ViewGroup.LayoutParams lp = getLayoutParams();
		if (lp == null) return;
		int h = Math.max(own, minBarHeight);
		// Taller than the minimum: the padding would only squeeze the buttons.
		if ((h > minBarHeight) && ((getPaddingTop() != 0) || (getPaddingBottom() != 0))) {
			setPadding(getPaddingLeft(), 0, getPaddingRight(), 0);
		}
		if (lp.height == h) return;
		lp.height = h;
		setLayoutParams(lp);
	}

	/** The buttons' icon colour (e.g. the nav bar's, for the two to match), or null for the style's. */
	public void setIconTint(@Nullable ColorStateList tint) {
		iconTint = tint;
		if (tint == null) return;
		for (int i = 0, n = getChildCount(); i < n; i++) applyIconTint(getChildAt(i));
	}

	private void applyIconTint(View v) {
		if ((iconTint != null) && (v instanceof ImageButton b)) b.setImageTintList(iconTint);
	}

	@Override
	public void onViewAdded(View child) {
		super.onViewAdded(child);
		applyIconTint(child);
		readableHint(child);
	}

	/**
	 * A text field's hint (e.g. "Search YouTube") in its own text colour, dimmed: the theme's hint
	 * colour is dark on some themes, unreadable on the tool bar's dark pill.
	 */
	private static void readableHint(View v) {
		if (v instanceof EditText e) {
			int c = e.getCurrentTextColor();
			e.setHintTextColor((c & 0x00FFFFFF) | 0x99000000);
		} else if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				if (g.getChildAt(i) instanceof EditText e) readableHint(e);
			}
		}
	}

	public void setSize(float scale) {
		Context ctx = getContext();
		float ts = getTextAppearanceSize(ctx, textAppearance) * scale;
		float ets = getTextAppearanceSize(ctx, editTextAppearance) * scale;
		sizeScale = scale;
		applyHeight((int) (size * scale));

		for (int i = 0, n = getChildCount(); i < n; i++) {
			View v = getChildAt(i);
			if (v instanceof EditText) ((EditText) v).setTextSize(COMPLEX_UNIT_PX, ets);
			else if (v instanceof TextView) ((TextView) v).setTextSize(COMPLEX_UNIT_PX, ts);
		}
	}

	public void setIconScale(float scale) {
		for (int i = 0, n = getChildCount(); i < n; i++) {
			View v = getChildAt(i);
			if (v instanceof ImageView) {
				v.setScaleX(scale);
				v.setScaleY(scale);
			}
		}
	}

	public Mediator getMediator() {
		return mediator;
	}

	protected void setMediator(Mediator mediator) {
		this.mediator = mediator;
	}

	protected boolean setMediator(ActivityFragment f) {
		boolean attached = attachMediator(this, f, (f == null) ? null : f::getToolBarMediator,
				this::getMediator, this::setMediator);
		if (!attached || (f == null)) return false;
		animateContentIn();
		float scale = f.getActivityDelegate().getToolBarSize();
		if (scale != 1F) {
			setSize(scale);
		} else {
			// Mutating the LayoutParams object alone doesn't request a new layout pass -- unlike
			// setSize(), which does via setLayoutParams() below. Without it, this toolbar can keep
			// rendering at whatever height a previous fragment's setSize(scale) last left it at until
			// some unrelated layout pass happens to pick up the mutated value, clipping this title
			// text into (or letting it visually spill into) the fragment content below it.
			sizeScale = 1F;
			applyHeight(size);
			setLayoutParams(getLayoutParams());
		}
		setIconScale(f.getActivityDelegate().getIconSize());
		return true;
	}

	/**
	 * Keeps a crowded toolbar usable, on a phone especially: when the buttons don't all fit next to
	 * the title/text field, the lowest-priority ones (the rightmost first, on a tie) move into a
	 * "more" menu at the far right, and come back as soon as there is room again (rotation, a wider
	 * window, the owner hiding other buttons). Only plain {@link ImageButton}s move; back/filter
	 * buttons ({@link ForcedVisibilityButton}) and buttons with {@link Integer#MAX_VALUE} priority
	 * stay put.
	 */
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		updateOverflow(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

	private void updateOverflow(int width, int height) {
		ViewGroup.LayoutParams tlp = getLayoutParams();
		int h = ((tlp != null) && (tlp.height > 0)) ? tlp.height : height;
		h -= getPaddingTop() + getPaddingBottom(); // The buttons' height, and so their width
		if ((width <= 0) || (h <= 0)) return;

		List<ImageButton> movable = new ArrayList<>();
		int fixed = 0; // Buttons that stay regardless
		boolean hasText = false;

		for (int i = 0, n = getChildCount(); i < n; i++) {
			View v = getChildAt(i);
			if (v.getId() == R.id.tool_bar_overflow) continue;
			if ((v instanceof ImageButton b) && !(v instanceof ForcedVisibilityButton) &&
					(b.getToolBarPriority() != Integer.MAX_VALUE)) {
				if (b.getRequestedVisibility() == VISIBLE) movable.add(b);
				else b.setOverflowed(false);
			} else if (v.getVisibility() == VISIBLE) {
				if (v instanceof ImageView) fixed++;
				else if (v instanceof TextView) hasText = true;
			}
		}

		// A title/text field shrinks to nothing rather than pushing buttons off, so keep it some room
		int avail = width - (hasText ? Math.max(width * 3 / 10, toIntPx(getContext(), 96)) : 0);
		int slots = Math.max(0, avail / h - fixed);
		int keep = (movable.size() <= slots) ? movable.size() : Math.max(0, slots - 1);

		List<ImageButton> order = new ArrayList<>(movable);
		// Stable: on a tie, the rightmost (last added) goes first
		order.sort((a, b) -> Integer.compare(b.getToolBarPriority(), a.getToolBarPriority()));
		for (int i = 0; i < order.size(); i++) order.get(i).setOverflowed(i >= keep);
		setOverflowButtonVisible(keep < movable.size());
	}

	private void setOverflowButtonVisible(boolean visible) {
		View ob = findViewById(R.id.tool_bar_overflow);

		if (visible) {
			if ((ob != null) && (indexOfChild(ob) == getChildCount() - 1)) return;
			if (ob != null) unlink(ob);
			Mediator m = getMediator();
			if (m == null) return;
			ImageButton b = new ImageButton(getContext(), null, androidx.appcompat.R.attr.toolbarStyle);
			m.initButton(b, R.drawable.tool_bar_overflow, v -> showOverflowMenu());
			b.setContentDescription(getContext().getString(R.string.tool_bar_more));
			b.setToolBarPriority(Integer.MAX_VALUE);
			float scale = getActivity().getIconSize();
			b.setScaleX(scale);
			b.setScaleY(scale);
			m.addView(this, b, R.id.tool_bar_overflow, RIGHT);
		} else if (ob != null) {
			unlink(ob);
		}
	}

	/** Removes a child from the chain, joining its neighbours. */
	private void unlink(View v) {
		int idx = indexOfChild(v);
		if (idx < 0) return;
		View l = (idx > 0) ? getChildAt(idx - 1) : null;
		View r = (idx < getChildCount() - 1) ? getChildAt(idx + 1) : null;

		if (l != null) {
			ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) l.getLayoutParams();
			if (r != null) {
				lp.endToStart = r.getId();
				lp.endToEnd = UNSET;
			} else {
				lp.endToStart = UNSET;
				lp.endToEnd = PARENT_ID;
			}
			lp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
		}
		if (r != null) {
			ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) r.getLayoutParams();
			if (l != null) {
				lp.startToEnd = l.getId();
				lp.startToStart = UNSET;
			} else {
				lp.startToEnd = UNSET;
				lp.startToStart = PARENT_ID;
			}
			lp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
		}

		removeView(v);
	}

	private void showOverflowMenu() {
		List<ImageButton> hidden = new ArrayList<>();
		for (int i = 0, n = getChildCount(); i < n; i++) {
			if ((getChildAt(i) instanceof ImageButton b) && b.isOverflowed()) hidden.add(b);
		}
		if (hidden.isEmpty()) return;

		OverlayMenu menu = getActivity().getToolBarMenu();
		if (menu == null) return;
		menu.show(mb -> {
			for (ImageButton b : hidden) {
				Drawable d = b.getDrawable();
				Drawable.ConstantState cs = (d != null) ? d.getConstantState() : null;
				if (cs != null) d = cs.newDrawable(getResources()).mutate();
				mb.addItem(b.getId(), d, getButtonLabel(b)).setData(b);
			}
			mb.setSelectionHandler(item -> {
				if (item.getData() instanceof ImageButton b) {
					// After the menu is gone: most buttons open a menu of their own
					post(b::performClick);
				}
				return true;
			});
		});
	}

	private CharSequence getButtonLabel(ImageButton b) {
		CharSequence d = b.getContentDescription();
		if (!TextUtils.isEmpty(d)) return d;
		String name;
		try {
			name = getResources().getResourceEntryName(b.getId());
		} catch (Exception ex) {
			return "";
		}
		for (String p : new String[]{"tool_bar_", "tool_", "browser_", "youtube_"}) {
			if (name.startsWith(p)) {
				name = name.substring(p.length());
				break;
			}
		}
		name = name.replace('_', ' ');
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	public boolean onBackPressed() {
		Mediator m = getMediator();
		return (m != null) && m.onBackPressed(this);
	}

	public ActivityDelegate getActivity() {
		return ActivityDelegate.get(getContext());
	}

	public ActivityFragment getActiveFragment() {
		return getActivity().getActiveFragment();
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		return getActivity().interceptTouchEvent(e, super::onTouchEvent);
	}

	@Override
	public void onActivityEvent(ActivityDelegate a, long e) {
		if (!handleActivityDestroyEvent(a, e)) {
			if (e == FRAGMENT_CHANGED) {
				if (setMediator(a.getActiveFragment())) return;
			}

			Mediator m = getMediator();
			if (m != null) m.onActivityEvent(this, a, e);
		} else {
			Mediator m = getMediator();
			if (m != null) m.disable(this);
		}
	}

	public EditText getFilter() {
		if (mediator instanceof Mediator.BackTitleFilter) {
			return findViewById(((Mediator.BackTitleFilter) mediator).getFilterId());
		} else {
			return findViewById(R.id.tool_bar_filter);
		}
	}

	@Override
	public Collection<ListenerRef<Listener>> getBroadcastEventListeners() {
		return listeners;
	}

	public View focusSearch() {
		View v = findViewById(R.id.tool_bar_back_button);
		return ((v != null) && isVisible(v)) ? v : this;
	}

	@Override
	public View focusSearch(View focused, int direction) {
		View v = getMediator().focusSearch(this, focused, direction);
		return (v != null) ? v : super.focusSearch(focused, direction);
	}

	public interface Listener {
		byte FILTER_CHANGED = 1;

		void onToolBarEvent(ToolBarView tb, byte event);
	}

	public interface Mediator extends ViewFragmentMediator<ToolBarView> {

		@Override
		default void enable(ToolBarView tb, ActivityFragment f) {
			tb.setVisibility(VISIBLE);
		}

		@Override
		default void disable(ToolBarView tb) {
			tb.removeAllViews();
		}

		default boolean onBackPressed(ToolBarView tb) {
			return false;
		}

		@Nullable
		default View focusSearch(ToolBarView tb, View focused, int direction) {
			ActivityDelegate a = ActivityDelegate.get(tb.getContext());
			if (direction == FOCUS_DOWN) {
				if (a.getActiveMenu() instanceof View v) return v.findFocus();
			}
			if (direction != FOCUS_UP) return null;
			NavBarView nb = a.getNavBar();
			return nb.isBottom() && isVisible(nb) ? nb.focusSearch() : null;
		}

		default void addView(ToolBarView tb, View v, @IdRes int id) {
			addView(tb, v, id, RIGHT);
		}

		default void addView(ToolBarView tb, View v, @IdRes int id, int side) {
			ConstraintLayout.LayoutParams lp;
			// Guards against a stale child stacking up behind the new one (observed as overlapping
			// title text) if a caller ever adds the same id twice without disable() (removeAllViews())
			// running in between -- e.g. a Mediator singleton shared across fragments skips its own
			// enable()/disable() entirely when the mediator instance doesn't change across a fragment
			// switch, but any other path that re-adds a view with an id already present here would
			// otherwise leave the old one attached and drawn underneath.
			View existing = tb.findViewById(id);
			if ((existing != null) && (existing != v)) tb.removeView(existing);
			v.setId(id);

			if (tb.getChildCount() == 0) {
				tb.addView(v);
				lp = (ConstraintLayout.LayoutParams) v.getLayoutParams();
				if (side == RIGHT) lp.endToEnd = PARENT_ID;
				else lp.startToStart = PARENT_ID;
			} else if (side == RIGHT) {
				View rv = tb.getChildAt(tb.getChildCount() - 1);
				ConstraintLayout.LayoutParams rlp = (ConstraintLayout.LayoutParams) rv.getLayoutParams();
				rlp.endToStart = id;
				rlp.endToEnd = UNSET;
				rlp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				tb.addView(v);
				lp = (ConstraintLayout.LayoutParams) v.getLayoutParams();
				lp.endToEnd = PARENT_ID;
			} else {
				View lv = tb.getChildAt(0);
				ConstraintLayout.LayoutParams llp = (ConstraintLayout.LayoutParams) lv.getLayoutParams();
				llp.startToEnd = id;
				llp.startToStart = UNSET;
				llp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				tb.addView(v, 0);
				lp = (ConstraintLayout.LayoutParams) v.getLayoutParams();
				lp.startToStart = PARENT_ID;
			}

			lp.topToTop = lp.bottomToBottom = PARENT_ID;
			lp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
		}

		default void addViewAt(ToolBarView tb, View v, @IdRes int id, int idx) {
			ConstraintLayout.LayoutParams lp = null;
			tb.addView(v, idx);
			int count = tb.getChildCount();
			v.setId(id);

			if (idx > 0) {
				View lv = tb.getChildAt(idx - 1);
				lp = (ConstraintLayout.LayoutParams) lv.getLayoutParams();
				lp.endToStart = id;
				lp.endToEnd = UNSET;
				lp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				lp = (ConstraintLayout.LayoutParams) v.getLayoutParams();
				lp.startToEnd = lv.getId();
				lp.startToStart = UNSET;
			}
			if (idx < count - 1) {
				View rv = tb.getChildAt(idx + 1);
				lp = (ConstraintLayout.LayoutParams) rv.getLayoutParams();
				lp.startToEnd = id;
				lp.startToStart = UNSET;
				lp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				lp = (ConstraintLayout.LayoutParams) v.getLayoutParams();
				lp.endToStart = rv.getId();
				lp.endToEnd = UNSET;
			}

			assert lp != null;
			lp.topToTop = lp.bottomToBottom = PARENT_ID;
			lp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
		}

		default ImageButton addButton(ToolBarView tb, @DrawableRes int icon, OnClickListener onClick,
																	@IdRes int id) {
			return addButton(tb, icon, onClick, id, RIGHT);
		}

		default ImageButton addButton(ToolBarView tb, @DrawableRes int icon, OnClickListener onClick,
																	@IdRes int id, int side) {
			ImageButton b = new ImageButton(tb.getContext(), null,
					androidx.appcompat.R.attr.toolbarStyle);
			initButton(b, icon, onClick);
			addView(tb, b, id, side);
			return b;
		}

		default <B extends ImageButton> B initButton(B b, @DrawableRes int icon, OnClickListener onClick) {
			ConstraintLayout.LayoutParams lp = setLayoutParams(b, 0, MATCH_PARENT);
			lp.horizontalWeight = 1;
			lp.dimensionRatio = "1:1";
			b.setImageResource(icon);
			b.setScaleType(ImageView.ScaleType.FIT_CENTER);
			b.setBackgroundResource(R.drawable.tool_bar_button_bg);
			if (onClick != null) b.setOnClickListener(onClick);
			setButtonPadding(b);
			return b;
		}

		default ConstraintLayout.LayoutParams setLayoutParams(View v, int width, int height) {
			ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(width, height);
			v.setLayoutParams(lp);
			return lp;
		}

		default void setButtonPadding(View v) {
			float scale = ActivityDelegate.get(v.getContext()).getToolBarSize();
			int pad = toIntPx(v.getContext(), Math.round(10 * scale));
			v.setPadding(pad, pad, pad, pad);
		}

		default EditText createEditText(ToolBarView tb) {
			Context ctx = tb.getContext();
			int p = (int) toPx(ctx, 5);
			EditText t = ActivityDelegate.get(ctx).createEditText(ctx);
			t.setTextAppearance(tb.editTextAppearance);
			t.setPadding(p, p, p, p);
			return t;
		}

		default Mediator join(Mediator m) {
			class Joint extends JointMediator<ToolBarView, Mediator> implements Mediator {
				public Joint(Mediator m1, Mediator m2) {
					super(m1, m2);
				}

				@Override
				public boolean onBackPressed(ToolBarView tb) {
					return m1.onBackPressed(tb) | m2.onBackPressed(tb);
				}
			}

			return new Joint(Mediator.this, m);
		}

		interface Invisible extends Mediator {
			Invisible instance = new Invisible() {
			};

			@Override
			default void enable(ToolBarView tb, ActivityFragment f) {
				tb.setVisibility(GONE);
			}
		}

		/**
		 * Back button and title.
		 */
		interface BackTitle extends Mediator, OnClickListener {
			BackTitle instance = new BackTitle() {
			};

			@Override
			default void enable(ToolBarView tb, ActivityFragment f) {
				Mediator.super.enable(tb, f);
				TextView t = createTitleText(tb);
				addView(tb, t, getTitleId(), LEFT);
				t.setText(f.getTitle());
				if (backOnTitleClick()) t.setOnClickListener(this);

				ImageButton b = createBackButton(tb);
				addView(tb, b, getBackButtonId(), LEFT);
				b.setVisibility(getBackButtonVisibility(f));
			}

			@Override
			default void onActivityEvent(ToolBarView tb, ActivityDelegate a, long e) {
				switch ((int) e) {
					case FRAGMENT_CHANGED:
					case FRAGMENT_CONTENT_CHANGED:
						ActivityFragment f = tb.getActiveFragment();
						if (f == null) return;
						ImageButton b = tb.findViewById(getBackButtonId());
						TextView t = tb.findViewById(getTitleId());
						b.setVisibility(getBackButtonVisibility(f));
						t.setText(f.getTitle());
						break;
				}
			}

			@Override
			default void onClick(View v) {
				if (v.getId() == getTitleId()) {
					ToolBarView tb = ActivityDelegate.get(v.getContext()).getToolBar();
					ForcedVisibilityButton b = tb.findViewById(getBackButtonId());
					if (!b.isVisible()) return;
				}

				ActivityDelegate.get(v.getContext()).onBackPressed();
			}

			@IdRes
			default int getTitleId() {
				return R.id.tool_bar_title;
			}

			default TextView createTitleText(ToolBarView tb) {
				Context ctx = tb.getContext();
				MaterialTextView t = new MaterialTextView(ctx, null,
						androidx.appcompat.R.attr.toolbarStyle);
				t.setTextAppearance(tb.textAppearance);
				t.setMaxLines(1);
				t.setFocusable(false);
				t.setEllipsize(TextUtils.TruncateAt.END);
				ConstraintLayout.LayoutParams lp = setLayoutParams(t, 0, WRAP_CONTENT);
				lp.horizontalWeight = 2;
				lp.endToEnd = PARENT_ID;
				// The title is constrained to sit right after the back button (see enable()), which
				// gives it natural breathing room whenever that button is visible. When the button is
				// GONE (a root page), that constraint collapses to 0 and the title runs flush against
				// the toolbar's edge; goneStartMargin only applies while the constrained-to view is
				// GONE, so it fills in that gap without touching the back-button case.
				float scale = ActivityDelegate.get(ctx).getToolBarSize();
				lp.goneStartMargin = toIntPx(ctx, Math.round(16 * scale));
				return t;
			}

			default boolean backOnTitleClick() {
				return true;
			}

			@IdRes
			default int getBackButtonId() {
				return R.id.tool_bar_back_button;
			}

			@DrawableRes
			default int getBackButtonIcon() {
				return R.drawable.back;
			}

			default ForcedVisibilityButton createBackButton(ToolBarView tb) {
				ForcedVisibilityButton b = new ForcedVisibilityButton(tb.getContext(), null,
						androidx.appcompat.R.attr.toolbarStyle);
				initButton(b, getBackButtonIcon(), this);
				return b;
			}

			default int getBackButtonVisibility(ActivityFragment f) {
				return f.getActivityDelegate().isRootPage() ? GONE : VISIBLE;
			}
		}

		/**
		 * Back button, title and filter.
		 */
		interface BackTitleFilter extends BackTitle {
			BackTitleFilter instance = new BackTitleFilter() {
			};

			@Override
			default void enable(ToolBarView tb, ActivityFragment fr) {
				EditText f = createFilter(tb);
				f.setVisibility(GONE);
				addView(tb, f, getFilterId(), LEFT);

				BackTitle.super.enable(tb, fr);

				ForcedVisibilityButton b = createFilterButton(tb);
				addView(tb, b, getFilterButtonId());
				setFilterVisibility(tb, false);
			}


			default void setFilterVisibility(ToolBarView tb, boolean visible) {
				EditText f = tb.findViewById(getFilterId());
				TextView t = tb.findViewById(getTitleId());
				ForcedVisibilityButton bb = tb.findViewById(getBackButtonId());
				ForcedVisibilityButton fb = tb.findViewById(getFilterButtonId());
				ConstraintLayout.LayoutParams flp = (ConstraintLayout.LayoutParams) f.getLayoutParams();
				ConstraintLayout.LayoutParams tlp = (ConstraintLayout.LayoutParams) t.getLayoutParams();

				if (visible) {
					bb.forceVisibility(true);
					fb.setVisibility(GONE);
					t.setVisibility(GONE);
					f.setVisibility(VISIBLE);
					f.requestFocus();

					flp.horizontalWeight = 2;
					flp.startToEnd = getBackButtonId();
					flp.endToStart = getFilterButtonId();
					flp.startToStart = UNSET;
					flp.endToEnd = UNSET;

					tlp.horizontalWeight = 0;
					tlp.startToStart = UNSET;
					tlp.startToEnd = UNSET;
					tlp.endToStart = UNSET;
					tlp.endToEnd = UNSET;
				} else {
					bb.forceVisibility(false);
					fb.setVisibility(VISIBLE);
					t.setVisibility(VISIBLE);
					f.setVisibility(GONE);
					f.setText("");
					f.clearFocus();

					tlp.horizontalWeight = 2;
					tlp.startToEnd = getBackButtonId();
					tlp.endToStart = getFilterButtonId();
					tlp.startToStart = UNSET;
					tlp.endToEnd = UNSET;

					flp.horizontalWeight = 0;
					flp.startToStart = UNSET;
					flp.startToEnd = UNSET;
					flp.endToStart = UNSET;
					flp.endToEnd = UNSET;
				}

				tlp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				flp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
			}

			@Override
			default void onClick(View v) {
				if (v.getId() == getFilterButtonId()) {
					setFilterVisibility((ToolBarView) v.getParent(), true);
				} else {
					BackTitle.super.onClick(v);
				}
			}

			@Override
			default boolean onBackPressed(ToolBarView tb) {
				EditText f = tb.findViewById(getFilterId());
				if ((f != null) && (f.getVisibility() == VISIBLE)) {
					setFilterVisibility(tb, false);
					return true;
				} else {
					return false;
				}
			}

			@Override
			default void onActivityEvent(ToolBarView tb, ActivityDelegate a, long e) {
				if (e == FRAGMENT_CHANGED) setFilterVisibility(tb, false);
				BackTitle.super.onActivityEvent(tb, a, e);
			}

			@IdRes
			default int getFilterId() {
				return R.id.tool_bar_filter;
			}

			default EditText createFilter(ToolBarView tb) {
				EditText t = createEditText(tb);
				TextChangedListener l = s -> tb.fireBroadcastEvent(r -> r.onToolBarEvent(tb, Listener.FILTER_CHANGED));
				t.addTextChangedListener(l);
				t.setBackgroundResource(R.color.tool_bar_edittext_bg);
				setLayoutParams(t, 0, WRAP_CONTENT);
				t.setOnKeyListener((v,c,e)->{
					if (e.getAction() != KeyEvent.ACTION_DOWN) return false;

					switch (c) {
						case KEYCODE_DPAD_UP:
						case KEYCODE_DPAD_DOWN:
							var next = v.focusSearch(c == KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);

							if (next != null) {
								next.requestFocus();
								return true;
							}
						case KeyEvent.KEYCODE_ENTER:
						case KeyEvent.KEYCODE_DPAD_CENTER:
						case KeyEvent.KEYCODE_NUMPAD_ENTER:
							setFilterVisibility(tb, false);
							var imm = (InputMethodManager) v.getContext().getSystemService(INPUT_METHOD_SERVICE);
							imm.hideSoftInputFromWindow(t.getWindowToken(), 0);
							return true;
						default:
							return false;
					}
				});
				return t;
			}

			@IdRes
			default int getFilterButtonId() {
				return R.id.tool_bar_filter_button;
			}

			@DrawableRes
			default int getFilterButtonIcon() {
				return R.drawable.filter;
			}

			default ForcedVisibilityButton createFilterButton(ToolBarView tb) {
				ForcedVisibilityButton b = new ForcedVisibilityButton(tb.getContext(), null,
						androidx.appcompat.R.attr.toolbarStyle);
				initButton(b, getFilterButtonIcon(), this);
				return b;
			}
		}
	}
}
