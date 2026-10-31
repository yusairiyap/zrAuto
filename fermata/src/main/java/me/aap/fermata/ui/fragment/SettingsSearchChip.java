package me.aap.fermata.ui.fragment;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import me.aap.fermata.R;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/** The title bar of every settings page: back button and title as usual, plus a Search chip at the right end that opens the settings search. */
final class SettingsSearchChip {
	private SettingsSearchChip() {
	}

	static final ToolBarView.Mediator MEDIATOR = new ToolBarView.Mediator.BackTitle() {
		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			ToolBarView.Mediator.BackTitle.super.enable(tb, f);
			Context ctx = tb.getContext();
			Context palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
					R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
			View chip = LayoutInflater.from(palette).inflate(R.layout.toolbar_chip, tb, false);
			TextView t = chip.findViewById(R.id.toolbar_chip_text);
			t.setText(R.string.settings_search_chip);
			t.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.search, 0, 0, 0);
			t.setOnClickListener(v -> me.aap.fermata.ui.activity.MainActivityDelegate.get(ctx)
					.showFragment(R.id.search_settings_fragment));
			addView(tb, chip, R.id.settings_search_chip);
		}
	};
}
