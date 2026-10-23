package me.aap.fermata.ui.view;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import me.aap.fermata.R;
import me.aap.fermata.util.DiagnosticLog;

/**
 * Lets a tab's background draw out past its own edges, under the status bar and the navigation bar, by switching
 * off the clipping of every container between its root and {@code main_activity}; and gives that clipping back.
 * <p>
 * The Music tab and the Home tab both do this to the same containers, and they overlap while one fades out and
 * the other fades in. Each used to remember "how the containers were" for itself: the second one to unclip then
 * remembered the first one's unclipped state as the original and put THAT back, leaving the containers unclipped
 * for good, so every other tab's list scrolled out behind the status bar with no fade over it (a see-through
 * status bar, until the app restarted). The original state is kept here once per container, with a count of the
 * tabs that want it unclipped; it is restored when the last of them lets go.
 */
public final class BarsClipping {
	private static final class State {
		boolean children;
		boolean toPadding;
		int users;
	}

	private static final Map<ViewGroup, State> states = new WeakHashMap<>();

	private BarsClipping() {
	}

	/** Unclips the containers above {@code root}; hand the result to {@link #release} when done. */
	public static ViewGroup[] acquire(View root) {
		List<ViewGroup> l = new ArrayList<>();
		for (ViewParent p = root.getParent(); p instanceof ViewGroup g; p = g.getParent()) {
			l.add(g);
			if (g.getId() == R.id.main_activity) break;
		}
		ViewGroup[] views = l.toArray(new ViewGroup[0]);
		for (ViewGroup g : views) {
			State s = states.get(g);
			if (s == null) {
				s = new State();
				s.children = g.getClipChildren();
				s.toPadding = g.getClipToPadding();
				states.put(g, s);
			}
			s.users++;
			g.setClipChildren(false);
			g.setClipToPadding(false);
		}
		return views;
	}

	/** One user lets go of {@code views}; a container nobody else wants unclipped gets its own clipping back. */
	public static void release(@Nullable ViewGroup[] views) {
		if (views == null) return;
		for (ViewGroup g : views) {
			State s = states.get(g);
			if (s == null) continue;
			if (--s.users > 0) continue;
			states.remove(g);
			g.setClipChildren(s.children);
			g.setClipToPadding(s.toPadding);
		}
		if (states.isEmpty()) DiagnosticLog.log("BODY", "containers clipped again");
	}
}
