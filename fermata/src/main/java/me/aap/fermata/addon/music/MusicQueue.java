package me.aap.fermata.addon.music;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.ExtRoot;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;

/**
 * The Music tab's play queue: an ordered, user-editable list of {@link MusicTrackItem}s, persisted
 * across restarts. It's a regular browsable container as far as the rest of the app is concerned,
 * so the media session's next/prev, Android Auto's queue view and the shared Repeat/Shuffle
 * preferences (stored on this item's own prefs) all work on it the same as on a folder --
 * with the order always being the queue's own, never sorted.
 */
public class MusicQueue extends ExtRoot {
	public static final String ID = "music";
	static final String SCHEME = "music";
	private static final String PREFS_NAME = "music_queue";
	private static final String KEY_TRACKS = "tracks";
	private static final String KEY_SERIAL = "serial";
	private static final String KEY_CURRENT = "current";
	private static final String KEY_POSITION = "position";
	private final SharedPreferences store;
	private final List<MusicTrackItem> tracks = new ArrayList<>();
	private final List<Listener> listeners = new ArrayList<>(2);
	private final Random random = new Random();
	@Nullable
	private List<MusicTrackItem> shuffleOrder;
	// Where the playing track was when it got removed from the queue: "next" carries on from there.
	private int removedCurrentIdx = -1;
	private long serial;

	MusicQueue(MediaLib lib) {
		super(ID, lib);
		store = lib.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		load();
	}

	public interface Listener {
		void onQueueChanged(MusicQueue queue);
	}

	public void addListener(Listener l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	@NonNull
	@Override
	public String getName() {
		return getLib().getContext().getString(R.string.music_queue);
	}

	@Override
	public int getIcon() {
		return R.drawable.music;
	}

	// A root: the base implementations would look for a parent.
	@Override
	protected FutureSupplier<String> buildTitle() {
		return completed(getName());
	}

	@Override
	protected FutureSupplier<String> buildSubtitle() {
		int n = size();
		return completed(getLib().getContext().getResources()
				.getQuantityString(R.plurals.music_queue_size, n, n));
	}

	public synchronized List<MusicTrackItem> getTracks() {
		return new ArrayList<>(tracks);
	}

	public synchronized int size() {
		return tracks.size();
	}

	public synchronized boolean isEmpty() {
		return tracks.isEmpty();
	}

	public synchronized int indexOf(Item track) {
		return tracks.indexOf(track);
	}

	@Nullable
	public synchronized MusicTrackItem getTrack(int idx) {
		return ((idx >= 0) && (idx < tracks.size())) ? tracks.get(idx) : null;
	}

	@Nullable
	synchronized MusicTrackItem findTrack(String id) {
		for (MusicTrackItem t : tracks) {
			if (t.getId().equals(id)) return t;
		}
		return null;
	}

	/** The source id a library item is queued by -- see {@link MusicTrackItem#getSourceId()}. */
	static String sourceIdOf(PlayableItem i) {
		if (i instanceof MusicTrackItem t) return t.getSourceId();
		String orig = i.getOrigId();
		if ((orig != null) && orig.startsWith(MusicTrackItem.YT_PREFIX)) return orig;
		return i.getId();
	}

	private MusicTrackItem newTrack(PlayableItem src) {
		String sourceId = sourceIdOf(src);
		String id = SCHEME + ':' + (++serial);

		if (src instanceof MusicTrackItem t) {
			return new MusicTrackItem(id, this, sourceId, t.getSource(), t.getCachedTitle(),
					t.getArtistName(), t.getCachedDuration());
		}

		String name = src.getName();
		return new MusicTrackItem(id, this, sourceId, src,
				((name == null) || name.isEmpty()) ? null : name, null, 0);
	}

	/**
	 * Replaces the whole queue with {@code items} (in order) and returns the new tracks, in the same
	 * order. The track for an item that's already playing as music is reused as-is.
	 */
	public List<MusicTrackItem> replace(List<? extends PlayableItem> items) {
		List<MusicTrackItem> added;

		synchronized (this) {
			tracks.clear();
			added = new ArrayList<>(items.size());
			for (PlayableItem i : items) added.add(newTrack(i));
			tracks.addAll(added);
			shuffleOrder = null;
		}

		changed();
		return added;
	}

	public List<MusicTrackItem> add(List<? extends PlayableItem> items) {
		return insert(-1, items);
	}

	/** Inserts right after {@code after} (or appends, if it isn't in the queue). */
	public List<MusicTrackItem> addAfter(@Nullable Item after, List<? extends PlayableItem> items) {
		int idx;
		synchronized (this) {
			idx = (after == null) ? -1 : tracks.indexOf(after);
		}
		return insert((idx == -1) ? -1 : idx + 1, items);
	}

	private List<MusicTrackItem> insert(int idx, List<? extends PlayableItem> items) {
		List<MusicTrackItem> added;

		synchronized (this) {
			added = new ArrayList<>(items.size());
			for (PlayableItem i : items) added.add(newTrack(i));
			if ((idx < 0) || (idx > tracks.size())) tracks.addAll(added);
			else tracks.addAll(idx, added);
			shuffleOrder = null;
		}

		changed();
		return added;
	}

	public void remove(int idx) {
		synchronized (this) {
			if ((idx < 0) || (idx >= tracks.size())) return;
			if (tracks.remove(idx).getId().equals(store.getString(KEY_CURRENT, null))) {
				removedCurrentIdx = idx;
			}
			shuffleOrder = null;
		}
		changed();
	}

	public void move(int from, int to) {
		synchronized (this) {
			int size = tracks.size();
			if ((from < 0) || (from >= size) || (to < 0) || (to >= size) || (from == to)) return;
			tracks.add(to, tracks.remove(from));
		}
		changed();
	}

	public void clear() {
		synchronized (this) {
			tracks.clear();
			shuffleOrder = null;
		}
		changed();
	}

	private void changed() {
		save();
		reset();
		for (Listener l : new ArrayList<>(listeners)) l.onQueueChanged(this);
	}

	/** Takes over a list's Shuffle and Repeat settings, when the queue is made from that list. */
	void copyModes(BrowsableItemPrefs from) {
		BrowsableItemPrefs p = getPrefs();
		p.setShufflePref(from.getShufflePref());
		p.setRepeatPref(from.getRepeatPref());
		p.setRepeatItemPref(null);
	}

	/** Remembers which track was playing, and where, so the queue can pick up from there later. */
	void setCurrent(@Nullable MusicTrackItem t, long position) {
		if ((t != null) && !t.getId().equals(store.getString(KEY_CURRENT, null))) {
			removedCurrentIdx = -1;
		}
		SharedPreferences.Editor e = store.edit();
		if (t == null) e.remove(KEY_CURRENT);
		else e.putString(KEY_CURRENT, t.getId());
		e.putLong(KEY_POSITION, Math.max(0, position));
		e.apply();
	}

	/** The track {@link #setCurrent} last saved, if it's still in the queue. */
	@Nullable
	public MusicTrackItem getSavedCurrent() {
		String id = store.getString(KEY_CURRENT, null);
		return (id == null) ? null : findTrack(id);
	}

	public long getSavedPosition() {
		return store.getLong(KEY_POSITION, 0);
	}

	@Override
	protected FutureSupplier<List<Item>> listChildren() {
		List<Item> children = new ArrayList<>(getTracks());
		return completed(children);
	}

	// The queue's order is the user's own and every track's metadata is already local -- skip the
	// base class's sorting and bulk metadata loading entirely.
	@NonNull
	@Override
	public FutureSupplier<List<Item>> getChildren() {
		return listChildren();
	}

	@NonNull
	@Override
	public FutureSupplier<List<Item>> getUnsortedChildren() {
		return listChildren();
	}

	@Override
	public boolean sortChildrenEnabled() {
		return false;
	}

	@NonNull
	@Override
	public FutureSupplier<Iterator<PlayableItem>> getShuffleIterator() {
		List<PlayableItem> l = new ArrayList<>(getTracks());
		Collections.shuffle(l, random);
		return completed(l.iterator());
	}

	/**
	 * Next/previous track after {@code t}, honouring this queue's Repeat One / Repeat / Shuffle
	 * preferences. Shuffle walks a fixed random order (rebuilt whenever the queue changes), so
	 * "previous" goes back to what actually played before, like any music player.
	 */
	FutureSupplier<PlayableItem> getPlayable(MusicTrackItem t, boolean next) {
		BrowsableItemPrefs p = getPrefs();
		if (t.getId().equals(p.getRepeatItemPref()) && (indexOf(t) != -1)) return completed(t);
		boolean repeat = p.getRepeatPref();
		MusicTrackItem result;

		synchronized (this) {
			int size = tracks.size();
			if (size == 0) return completedNull();
			List<MusicTrackItem> order;

			if (p.getShufflePref()) {
				order = shuffleOrder;
				if ((order == null) || (order.size() != size) || !order.contains(t)) {
					order = new ArrayList<>(tracks);
					Collections.shuffle(order, random);
					if (order.remove(t)) order.add(0, t);
					shuffleOrder = order;
				}
			} else {
				order = tracks;
			}

			int idx = order.indexOf(t);

			if (idx == -1) {
				// Removed from the queue while playing: carry on from wherever it used to be.
				int at = (!p.getShufflePref() && (removedCurrentIdx >= 0)) ? removedCurrentIdx : 0;
				if (next) result = (at < size) ? order.get(at) : (repeat ? order.get(0) : null);
				else result = ((at > 0) && (at <= size)) ? order.get(at - 1) : null;
			} else if (next) {
				result = (idx < size - 1) ? order.get(idx + 1) : (repeat ? order.get(0) : null);
			} else {
				result = (idx > 0) ? order.get(idx - 1) : (repeat ? order.get(size - 1) : null);
			}
		}

		if (result == null) return completedNull();
		return completed(result);
	}

	private void save() {
		JSONArray a = new JSONArray();

		try {
			for (MusicTrackItem t : getTracks()) {
				JSONObject o = new JSONObject();
				o.put("i", t.getId());
				o.put("s", t.getSourceId());
				String title = t.getCachedTitle();
				String artist = t.getArtistName();
				if (title != null) o.put("t", title);
				if (artist != null) o.put("a", artist);
				long d = t.getCachedDuration();
				if (d > 0) o.put("d", d);
				a.put(o);
			}
		} catch (JSONException ex) {
			Log.e(ex, "Failed to save the music queue");
			return;
		}

		store.edit().putString(KEY_TRACKS, a.toString()).putLong(KEY_SERIAL, serial).apply();
	}

	private void load() {
		serial = store.getLong(KEY_SERIAL, 0);
		String json = store.getString(KEY_TRACKS, null);
		if (json == null) return;

		try {
			JSONArray a = new JSONArray(json);

			for (int i = 0, n = a.length(); i < n; i++) {
				JSONObject o = a.optJSONObject(i);
				if (o == null) continue;
				String id = o.optString("i");
				String src = o.optString("s");
				if (id.isEmpty() || src.isEmpty()) continue;
				tracks.add(new MusicTrackItem(id, this, src, null, emptyToNull(o.optString("t")),
						emptyToNull(o.optString("a")), o.optLong("d", 0)));
			}
		} catch (JSONException ex) {
			Log.e(ex, "Failed to load the music queue");
		}
	}

	@Nullable
	private static String emptyToNull(String s) {
		return ((s == null) || s.isEmpty()) ? null : s;
	}
}
