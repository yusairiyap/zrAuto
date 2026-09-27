package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The fifth floating button: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class QuinaryFabMediator extends ActionFabMediator {
	public static final QuinaryFabMediator instance = new QuinaryFabMediator();

	private QuinaryFabMediator() {
		super(MainActivityPrefs.FAB5_ACTION);
	}
}
