package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The extra floating button FAB9: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class NonaryFabMediator extends ActionFabMediator {
	public static final NonaryFabMediator instance = new NonaryFabMediator();

	private NonaryFabMediator() {
		super(MainActivityPrefs.FAB9_ACTION);
	}
}
