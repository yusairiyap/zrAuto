package me.aap.fermata.ui.fragment;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The extra floating button FAB8: runs the action set for it in Settings -- see {@link ActionFabMediator}. */
public final class OctonaryFabMediator extends ActionFabMediator {
	public static final OctonaryFabMediator instance = new OctonaryFabMediator();

	private OctonaryFabMediator() {
		super(MainActivityPrefs.FAB8_ACTION);
	}
}
