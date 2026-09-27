package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The fourth floating button: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class QuaternaryFabMediator extends ActionFabMediator {
	public static final QuaternaryFabMediator instance = new QuaternaryFabMediator();

	private QuaternaryFabMediator() {
		super(MainActivityPrefs.FAB4_ACTION);
	}
}
