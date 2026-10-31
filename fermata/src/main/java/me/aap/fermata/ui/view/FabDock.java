package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toIntPx;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.ActionFabMediator;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.view.FloatingButton;

/**
 * Keeps the floating buttons beside a panel docked on the right of fullscreen video: the row ends at the video's width
 * (the primary button's end margin follows the panel, the others chain from it), and what does not fit there is taken
 * out of the row and listed in a menu behind a "..." button at its end. Nothing here shrinks.
 */
public final class FabDock {
	private final MainActivityDelegate a;
	private float share;
	private int baseEnd = -1;
	@Nullable
	private ImageView more;
	/** The buttons taken out of the row now, in order. */
	private final List<FloatingButton> hidden = new ArrayList<>();
	@Nullable
	private View lastKept;

	public FabDock(MainActivityDelegate a) {
		this.a = a;
	}

	/** How much of the width the docked panel takes now (0 .. 0.8). */
	public void setShare(float share) {
		if (this.share == share) return;
		this.share = share;
		watch(share > 0f);
		// Works the visibility out again (the row's own rules), which ends in refresh().
		a.updateExtraFabsVisibility();
	}

	private android.view.ViewTreeObserver.OnGlobalLayoutListener glue;
	@Nullable
	private android.view.ViewTreeObserver watched;

	/**
	 * While a panel is docked the buttons' visibility is also changed by the control panel (they hide and show with it, which would
	 * bring back the ones taken out): every layout puts the row right again. Nothing changes when it already is, so it settles.
	 */
	private void watch(boolean on) {
		FloatingButton primary = a.getFloatingButton();
		if (primary == null) return;
		if (on && (glue == null)) {
			glue = this::refresh;
			watched = primary.getViewTreeObserver();
			watched.addOnGlobalLayoutListener(glue);
		} else if (!on && (glue != null)) {
			if ((watched != null) && watched.isAlive()) watched.removeOnGlobalLayoutListener(glue);
			glue = null;
			watched = null;
		}
	}

	/** Lays the row out for the current share; called after the buttons' visibility was worked out. */
	public void refresh() {
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
		hidden.clear();
		lastKept = null;
		if ((share <= 0f) || (pw <= 0)) {
			showMore(parent, false);
			return;
		}
		// What fits is worked out whether or not the buttons are showing now, so the "..." button is known to the fades that bring them.
		int gap = toIntPx(a.getContext(), 24);
		int dflt = toIntPx(a.getContext(), 56);
		int avail = pw - inset - 2 * baseEnd;
		List<View> extras = a.getEnabledExtraFabsRaw();
		int used = width(primary, dflt);
		int fits = 0;
		for (View v : extras) {
			int w = gap + width(v, dflt);
			if (used + w > avail) break;
			used += w;
			fits++;
		}
		if (fits == extras.size()) {
			showMore(parent, false);
			return;
		}
		// Room for the "..." button as well: one less.
		int moreW = gap + toIntPx(a.getContext(), 52);
		while ((fits > 0) && (used + moreW > avail)) {
			fits--;
			used -= gap + width(extras.get(fits), dflt);
		}
		boolean shown = primary.getVisibility() == View.VISIBLE;
		View last = primary;
		for (int i = 0; i < extras.size(); i++) {
			if (!(extras.get(i) instanceof FloatingButton fb)) continue;
			if (i < fits) {
				last = fb;
			} else {
				hidden.add(fb);
				if (shown && (fb.getVisibility() != View.GONE)) fb.setVisibility(View.GONE);
			}
		}
		lastKept = last;
		showMore(parent, true);
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
		// Follows the primary button's visibility (the fades of the control panel drive its alpha with the others').
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
				int idx = java.util.Arrays.asList(all).indexOf(fb);
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
