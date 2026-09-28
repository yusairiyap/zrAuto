package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The second floating button: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class SecondaryFabMediator extends ActionFabMediator {
	public static final SecondaryFabMediator instance = new SecondaryFabMediator();

	private SecondaryFabMediator() {
		super(MainActivityPrefs.FAB2_ACTION);
	}
}
