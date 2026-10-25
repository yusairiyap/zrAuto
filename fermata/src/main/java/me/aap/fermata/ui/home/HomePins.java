package me.aap.fermata.ui.home;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/** The playlists pinned to the top of the Playlists widget (kept as ids in a preference, the newest pin first). */
public final class HomePins {
	private HomePins() {
	}

	public static List<String> ids(MainActivityPrefs p) {
		List<String> l = new ArrayList<>();
		String s = p.getStringPref(MainActivityPrefs.PINNED_PLAYLISTS);
		if (s == null) return l;
		for (String id : s.split("\n")) {
			if (!id.isEmpty()) l.add(id);
		}
		return l;
	}

	public static boolean isPinned(MainActivityPrefs p, String id) {
		return ids(p).contains(id);
	}

	/** Pins the playlist to the top, or lets it go back to its own place. */
	public static void toggle(MainActivityPrefs p, String id) {
		List<String> l = ids(p);
		if (!l.remove(id)) l.add(0, id);
		p.applyStringPref(MainActivityPrefs.PINNED_PLAYLISTS, String.join("\n", l));
	}
}
