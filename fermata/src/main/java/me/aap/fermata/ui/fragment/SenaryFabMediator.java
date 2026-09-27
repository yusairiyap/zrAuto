package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The sixth floating button: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class SenaryFabMediator extends ActionFabMediator {
	public static final SenaryFabMediator instance = new SenaryFabMediator();

	private SenaryFabMediator() {
		super(MainActivityPrefs.FAB6_ACTION);
	}
}
