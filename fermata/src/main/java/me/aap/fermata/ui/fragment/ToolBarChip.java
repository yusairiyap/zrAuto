package me.aap.fermata.ui.fragment;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.view.ContextThemeWrapper;

import me.aap.fermata.R;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * A tab's main action as a chip (the Music tab's MusicChip look) at the right end of the title
 * bar wherever the screen is wide -- landscape and the car screen -- the same place the Music tab
 * puts its Info Overlay there; elsewhere the tab shows its own chip in its content instead. Used
 * for the Data Usage settings and the Fuel Log's Refuel.
 */
public final class ToolBarChip {

	/** The tab the chip belongs to. */
	public interface Host {
		void onToolBarChipClick();
	}

	private ToolBarChip() {
	}

	/** Landscape or the car screen: the chip goes in the title bar. */
	public static boolean isWide(MainActivityFragment f) {
		if (f.getContext() == null) return false;
		return f.getActivityDelegate().isCarActivity() ||
				(f.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
	}

	/**
	 * Shows either the title bar's chip or the tab's own one ({@code inContent}), whichever fits
	 * the screen now -- called again whenever the tab is laid out anew (e.g. rotated: this
	 * activity handles orientation changes itself, so the title bar isn't rebuilt then).
	 */
	public static void update(MainActivityFragment f, @IdRes int chipId, @Nullable View inContent) {
		if (f.getContext() == null) return;
		boolean wide = isWide(f);
		View chip = f.getActivityDelegate().getToolBar().findViewById(chipId);
		if (chip != null) chip.setVisibility(wide ? View.VISIBLE : View.GONE);
		if (inContent != null) inContent.setVisibility(wide ? View.GONE : View.VISIBLE);
	}

	/** Back button and title as usual, plus the chip at the right end. */
	public static class Mediator implements ToolBarView.Mediator.BackTitle {
		@IdRes
		private final int id;
		@DrawableRes
		private final int icon;
		@StringRes
		private final int text;

		public Mediator(@IdRes int id, @DrawableRes int icon, @StringRes int text) {
			this.id = id;
			this.icon = icon;
			this.text = text;
		}

		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			ToolBarView.Mediator.BackTitle.super.enable(tb, f);
			if (!(f instanceof MainActivityFragment mf) || !(f instanceof Host h)) return;
			Context ctx = tb.getContext();
			Context palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
					R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
			View chip = LayoutInflater.from(palette).inflate(R.layout.toolbar_chip, tb, false);
			TextView t = chip.findViewById(R.id.toolbar_chip_text);
			t.setText(text);
			t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
			t.setOnClickListener(v -> h.onToolBarChipClick());
			addView(tb, chip, id);
			chip.setVisibility(isWide(mf) ? View.VISIBLE : View.GONE);
		}
	}
}
