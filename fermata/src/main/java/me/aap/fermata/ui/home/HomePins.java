package me.aap.fermata.ui.home;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.pref.PreferenceStore;

/**
 * The playlists pinned to the top (kept as ids in a preference, the newest pin first): the Playlists tab and the Playlists widget
 * list them first and mark them with a pin.
 */
public final class HomePins {
	private HomePins() {
	}

	private static String lastRaw;
	private static Set<String> lastSet = new HashSet<>();

	public static List<String> ids(PreferenceStore p) {
		List<String> l = new ArrayList<>();
		String s = p.getStringPref(MainActivityPrefs.PINNED_PLAYLISTS);
		if (s == null) return l;
		for (String id : s.split("\n")) {
			if (!id.isEmpty()) l.add(id);
		}
		return l;
	}

	/** Whether the playlist is pinned; cheap enough to ask while drawing (the parsed ids are kept until the preference changes). */
	public static boolean isPinned(PreferenceStore p, String id) {
		String raw = p.getStringPref(MainActivityPrefs.PINNED_PLAYLISTS);
		if (raw == null) raw = "";
		synchronized (HomePins.class) {
			if (!raw.equals(lastRaw)) {
				lastRaw = raw;
				lastSet = new HashSet<>(ids(p));
			}
			return lastSet.contains(id);
		}
	}

	/** Pins the playlist to the top, or lets it go back to its own place. */
	public static void toggle(PreferenceStore p, String id) {
		List<String> l = ids(p);
		if (!l.remove(id)) l.add(0, id);
		p.applyStringPref(MainActivityPrefs.PINNED_PLAYLISTS, String.join("\n", l));
	}
}
