package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The extra floating button FAB7: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class SeptenaryFabMediator extends ActionFabMediator {
	public static final SeptenaryFabMediator instance = new SeptenaryFabMediator();

	private SeptenaryFabMediator() {
		super(MainActivityPrefs.FAB7_ACTION);
	}
}
