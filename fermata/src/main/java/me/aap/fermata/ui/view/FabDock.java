package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toIntPx;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.ActionFabMediator;
import me.aap.utils.ui.view.FloatingButton;

/**
 * Keeps the floating buttons beside a panel docked on the right of fullscreen video: the row ends at the video's width
 * (the primary button's end margin follows the panel, the others chain from it), and what does not fit there is taken
 * out of the row and listed in a menu behind a "..." button at its end. Nothing here shrinks.
 * <p>
 * Cheap on purpose: the buttons and their widths are read once when the dock opens or a button's visibility changes
 * ({@link #refresh}, driven by {@link FloatingButton#setVisibilityListener}, no layout listener), and each animation frame
 * only compares the room with that cache ({@link #relayout}).
 */
public final class FabDock {
	private final MainActivityDelegate a;
	private float share;
	private int baseEnd = -1;
	@Nullable
	private ImageView more;
	/** The extra buttons now enabled, and their widths, as of the last {@link #refresh}. */
	private List<View> extras = new ArrayList<>();
	private int[] widths = new int[0];
	private int primaryWidth;
	/** How many of {@link #extras} are in the row now (the rest are taken out); -1 before the first layout. */
	private int fits = -1;
	/** The buttons taken out of the row now, in order. */
	private final List<FloatingButton> hidden = new ArrayList<>();
	@Nullable
	private View lastKept;
	private boolean refreshing;
	private final Runnable onVisibility = this::visibilityChanged;

	public FabDock(MainActivityDelegate a) {
		this.a = a;
	}

	/** How much of the width the docked panel takes now (0 .. 0.8); called on every step of its animation. */
	public void setShare(float share) {
		if (this.share == share) return;
		boolean was = this.share > 0f;
		this.share = share;
		boolean now = share > 0f;
		if (now != was) {
			listen(now);
			if (!now) {
				fits = -1;
				hidden.clear();
			}
			// The row's own rules put every button as they want it (what does not fit is taken out of that again); it ends in
			// refresh(), so the visibility changes it makes meanwhile are not each answered.
			refreshing = true;
			try {
				a.updateExtraFabsVisibility();
			} finally {
				refreshing = false;
			}
			refresh();
		} else {
			relayout();
		}
	}

	private void listen(boolean on) {
		FloatingButton p = a.getFloatingButton();
		if (p != null) p.setVisibilityListener(on ? onVisibility : null);
		for (FloatingButton fb : a.getExtraFloatingButtons()) {
			if (fb != null) fb.setVisibilityListener(on ? onVisibility : null);
		}
	}

	/** A button showed or hid (the control panel's fades): the buttons taken out of the row are taken out again, nothing is read. */
	private void visibilityChanged() {
		if (refreshing || (share <= 0f)) return;
		relayout();
	}

	/** Reads the buttons again (they, or what shows of them, changed) and lays the row out. */
	public void refresh() {
		if (refreshing) return;
		FloatingButton primary = a.getFloatingButton();
		if (primary == null) return;
		refreshing = true;
		try {
			extras = a.getEnabledExtraFabsRaw();
			widths = new int[extras.size()];
			int dflt = toIntPx(a.getContext(), 56);
			for (int i = 0; i < widths.length; i++) widths[i] = width(extras.get(i), dflt);
			primaryWidth = width(primary, dflt);
			fits = -1;
			relayout();
		} finally {
			refreshing = false;
		}
	}

	/** The row for the current share, from what was read: only what changed since the last call is touched. */
	private void relayout() {
		FloatingButton primary = a.getFloatingButton();
		if ((primary == null) || !(primary.getParent() instanceof ConstraintLayout parent)) return;
		if (!(primary.getLayoutParams() instanceof ConstraintLayout.LayoutParams lp)) return;
		if (baseEnd < 0) baseEnd = lp.getMarginEnd();
		int pw = parent.getWidth();
		int inset = (share > 0f) ? Math.round(pw * share) : 0;
		if (lp.getMarginEnd() != baseEnd + inset) {
			lp.setMarginEnd(baseEnd + inset);
			primary.setLayoutParams(lp);
		}
		if ((share <= 0f) || (pw <= 0)) {
			if (hidden.size() > 0) hidden.clear();
			lastKept = null;
			showMore(parent, false);
			return;
		}
		int gap = toIntPx(a.getContext(), 24);
		int avail = pw - inset - 2 * baseEnd;
		int used = primaryWidth;
		int n = 0;
		for (int w : widths) {
			if (used + gap + w > avail) break;
			used += gap + w;
			n++;
		}
		if (n < extras.size()) {
			// Room for the "..." button as well: one less.
			int moreW = gap + toIntPx(a.getContext(), 52);
			while ((n > 0) && (used + moreW > avail)) {
				n--;
				used -= gap + widths[n];
			}
		}
		boolean shown = primary.getVisibility() == View.VISIBLE;
		if ((n != fits) || shown) {
			boolean changed = n != fits;
			fits = n;
			hidden.clear();
			View last = primary;
			refreshing = true;
			try {
				for (int i = 0; i < extras.size(); i++) {
					if (!(extras.get(i) instanceof FloatingButton fb)) continue;
					if (i < n) {
						last = fb;
					} else {
						hidden.add(fb);
						if (shown && (fb.getVisibility() != View.GONE)) fb.setVisibility(View.GONE);
					}
				}
			} finally {
				refreshing = false;
			}
			lastKept = last;
			if (changed || (more == null)) showMore(parent, n < extras.size());
		}
	}

	/** The buttons that fade and show together, with the ones taken out of the row left out and the "..." button added. */
	public List<View> adjust(List<View> l) {
		if (hidden.isEmpty()) return l;
		List<View> r = new ArrayList<>(l);
		r.removeAll(hidden);
		if (more != null) r.add(more);
		return r;
	}

	private static int width(View v, int dflt) {
		return (v.getWidth() > 0) ? v.getWidth() : dflt;
	}

	/** Makes the "..." button for a row that overflows (in the layout, not showing: the fades show it), or takes it out of the way. */
	private void showMore(ConstraintLayout parent, boolean overflow) {
		View last = lastKept;
		if (!overflow || (last == null)) {
			if ((more != null) && (more.getVisibility() != View.GONE)) more.setVisibility(View.GONE);
			return;
		}
		if (more == null) {
			more = VideoControlsOverlay.newButton(a.getContext(), R.drawable.more, toIntPx(a.getContext(), 52),
					toIntPx(a.getContext(), 14));
			more.setId(View.generateViewId());
			more.setElevation(toIntPx(a.getContext(), 10));
			more.setContentDescription(a.getContext().getString(R.string.fab_more));
			more.setOnClickListener(v -> showMenu());
			more.setVisibility(a.getFloatingButton().getVisibility());
			ConstraintLayout.LayoutParams p = new ConstraintLayout.LayoutParams(toIntPx(a.getContext(), 52),
					toIntPx(a.getContext(), 52));
			p.bottomToTop = R.id.control_panel;
			p.endToStart = last.getId();
			p.setMarginEnd(toIntPx(a.getContext(), 24));
			p.bottomMargin = toIntPx(a.getContext(), 15);
			parent.addView(more, p);
		} else if (more.getLayoutParams() instanceof ConstraintLayout.LayoutParams p) {
			if (p.endToStart != last.getId()) {
				p.endToStart = last.getId();
				more.setLayoutParams(p);
			}
		}
		// Follows the primary button (the control panel's fades drive its alpha with the others').
		int vis = a.getFloatingButton().getVisibility();
		if (more.getVisibility() != vis) more.setVisibility(vis);
	}

	/** What did not fit, as a menu of what each button does: a tap does what the button would have. */
	private void showMenu() {
		List<FloatingButton> l = new ArrayList<>(hidden);
		if (l.isEmpty()) return;
		FloatingButton[] all = a.getExtraFloatingButtons();
		a.getContextMenu().show(b -> {
			b.setTitle(a.getContext().getString(R.string.fab_more));
			for (FloatingButton fb : l) {
				int idx = Arrays.asList(all).indexOf(fb);
				Action act = (idx < 0) ? null :
						Action.get(a.getPrefs().getIntPref(MainActivityPrefs.fab(a, MainActivityPrefs.fabAction(idx + 2))));
				if (act == null) act = Action.NONE;
				CharSequence t = a.getContext().getString(ActionFabMediator.labelFor(a, act));
				b.addItem(View.generateViewId(), ActionFabMediator.iconFor(a, act), t).setHandler(i -> {
					fb.performClick();
					return true;
				});
			}
		});
	}
}
