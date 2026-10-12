package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The extra floating button FAB10: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class DenaryFabMediator extends ActionFabMediator {
	public static final DenaryFabMediator instance = new DenaryFabMediator();

	private DenaryFabMediator() {
		super(MainActivityPrefs.FAB10_ACTION);
	}
}
