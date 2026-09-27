package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The third floating button: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class TertiaryFabMediator extends ActionFabMediator {
	public static final TertiaryFabMediator instance = new TertiaryFabMediator();

	private TertiaryFabMediator() {
		super(MainActivityPrefs.FAB3_ACTION);
	}
}
