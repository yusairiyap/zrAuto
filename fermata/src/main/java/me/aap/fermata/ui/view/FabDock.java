package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toIntPx;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
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

	public FabDock(MainActivityDelegate a) {
		this.a = a;
	}

	/** How much of the width the docked panel takes now (0 .. 0.8). */
	public void setShare(float share) {
		if (this.share == share) return;
		this.share = share;
		// Works the visibility out again (the row's own rules), which ends in refresh().
		a.updateExtraFabsVisibility();
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
		if ((share <= 0f) || (pw <= 0) || (primary.getVisibility() != View.VISIBLE)) {
			showMore(parent, null);
			return;
		}
		int gap = toIntPx(a.getContext(), 24);
		int dflt = toIntPx(a.getContext(), 56);
		int avail = pw - inset - 2 * baseEnd;
		List<View> extras = a.getEnabledExtraFabs();
		int used = width(primary, dflt);
		int fits = 0;
		for (View v : extras) {
			int w = gap + width(v, dflt);
			if (used + w > avail) break;
			used += w;
			fits++;
		}
		if (fits == extras.size()) {
			showMore(parent, null);
			return;
		}
		// Room for the "..." button as well: one less.
		int moreW = gap + toIntPx(a.getContext(), 52);
		while ((fits > 0) && (used + moreW > avail)) {
			fits--;
			used -= gap + width(extras.get(fits), dflt);
		}
		View last = primary;
		for (int i = 0; i < extras.size(); i++) {
			if (!(extras.get(i) instanceof FloatingButton fb)) continue;
			if (i < fits) {
				last = fb;
			} else {
				fb.setVisibility(View.GONE);
				hidden.add(fb);
			}
		}
		showMore(parent, last);
	}

	private static int width(View v, int dflt) {
		return (v.getWidth() > 0) ? v.getWidth() : dflt;
	}

	/** Shows the "..." button after {@code last}, or hides it when null. */
	private void showMore(ConstraintLayout parent, @Nullable View last) {
		if (last == null) {
			if (more != null) more.setVisibility(View.GONE);
			return;
		}
		if (more == null) {
			more = VideoControlsOverlay.newButton(a.getContext(), R.drawable.more, toIntPx(a.getContext(), 52),
					toIntPx(a.getContext(), 14));
			more.setId(View.generateViewId());
			more.setElevation(toIntPx(a.getContext(), 10));
			more.setContentDescription(a.getContext().getString(R.string.fab_more));
			more.setOnClickListener(v -> showMenu());
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
		more.setVisibility(View.VISIBLE);
		// The panel's own controls hide and show with the video's; this one follows the primary button.
		more.setAlpha(a.getFloatingButton().getAlpha());
	}

	/** What did not fit, as a menu: a tap does what the button would have. */
	private void showMenu() {
		List<FloatingButton> l = new ArrayList<>(hidden);
		if (l.isEmpty()) return;
		a.getContextMenu().show(b -> {
			b.setTitle(a.getContext().getString(R.string.fab_more));
			for (FloatingButton fb : l) {
				Drawable d = fb.getDrawable();
				if ((d != null) && (d.getConstantState() != null)) d = d.getConstantState().newDrawable().mutate();
				CharSequence t = fb.getContentDescription();
				if (t == null) t = a.getContext().getString(R.string.fab_more);
				b.addItem(View.generateViewId(), d, t).setHandler(i -> {
					fb.performClick();
					return true;
				});
			}
		});
	}
}
