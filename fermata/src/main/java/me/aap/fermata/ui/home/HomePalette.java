package me.aap.fermata.ui.home;

import android.content.Context;

import me.aap.fermata.R;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.ui.view.EffectsUi;

/**
 * The colours the Home tab is drawn in: the Music tab's palette (dark or light to match the app
 * theme, see {@link EffectsUi#palette}), resolved once.
 */
public final class HomePalette {
	public final Context ctx;
	public final boolean light;
	public final int background;
	public final int textPrimary;
	public final int textSecondary;
	public final int textTertiary;
	public final int icon;
	public final int chipFill;
	public final int chipRipple;
	public final int cardFill;
	public final int panelFill;
	public final int rowFill;
	public final int playFill;
	public final int playIcon;
	public final int seekTrack;
	public final int accent;
	public final int onAccent;

	public HomePalette(Context base) {
		ctx = EffectsUi.palette(base);
		light = MusicPlayerFragment.isLightTheme(base);
		background = HomeUi.attr(ctx, R.attr.musicBackground);
		textPrimary = HomeUi.attr(ctx, R.attr.musicTextPrimary);
		textSecondary = HomeUi.attr(ctx, R.attr.musicTextSecondary);
		textTertiary = HomeUi.attr(ctx, R.attr.musicTextTertiary);
		icon = HomeUi.attr(ctx, R.attr.musicIconPrimary);
		chipFill = HomeUi.attr(ctx, R.attr.musicChipFill);
		chipRipple = HomeUi.attr(ctx, R.attr.musicChipRipple);
		cardFill = HomeUi.attr(ctx, R.attr.musicCardFill);
		panelFill = HomeUi.attr(ctx, R.attr.musicPanelFill);
		rowFill = HomeUi.attr(ctx, R.attr.musicRowFill);
		playFill = HomeUi.attr(ctx, R.attr.musicPlayFill);
		playIcon = HomeUi.attr(ctx, R.attr.musicPlayIcon);
		seekTrack = HomeUi.attr(ctx, R.attr.musicSeekTrack);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);
	}
}
