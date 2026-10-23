package me.aap.fermata.ui.home.widget;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;

/**
 * The Music tab's queue, in the order it plays: the song playing now marked and scrolled into view
 * and a tap on any song playing it right here. It follows the queue as the songs change.
 */
final class QueueWidget extends LibraryWidget {
	private final FermataServiceUiBinder.Listener itemListener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			post(() -> refresh(true));
		}
	};

	@Nullable
	private MusicQueue watched;
	private final MusicQueue.Listener queueListener = q -> post(() -> refresh(true));

	QueueWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.music_addon;
	}

	@Override
	protected boolean supportsReorder() {
		return true;
	}

	@Override
	protected boolean supportsRemove() {
		return true;
	}

	/** Takes the song out of the queue, as swiping it away does in the Music tab (positions in the play order). */
	@Override
	protected void onRemoved(int position) {
		MusicQueue q = MusicPlayer.getQueue(act());
		if (q != null) q.remove(position);
	}

	/** The same move as the Music tab's queue drag: positions in the play order, which is how the rows are listed. */
	@Override
	protected void onReordered(int from, int to) {
		MusicQueue q = MusicPlayer.getQueue(act());
		if (q != null) q.move(from, to);
	}

	@Override
	protected int emptyRes() {
		return R.string.home_queue_empty;
	}

	@Override
	protected void onActive() {
		super.onActive();
		act().getMediaServiceBinder().addBroadcastListener(itemListener);
		// Songs added, removed, moved or shuffled: the list follows the queue, not only the playing song.
		watch(MusicPlayer.getQueue(act()));
	}

	/**
	 * The queue may not exist yet when the card comes up (it is made the first time something is queued), so every read
	 * looks again and listens to the one it finds: otherwise a queue made later was only seen after the list went stale.
	 */
	private void watch(@Nullable MusicQueue q) {
		if (q == watched) return;
		if (watched != null) watched.removeListener(queueListener);
		watched = q;
		if ((q != null) && isActive()) q.addListener(queueListener);
	}

	@Override
	protected boolean followsLibrary() {
		return false;
	}

	@Override
	protected void onInactive() {
		act().getMediaServiceBinder().removeBroadcastListener(itemListener);
		if (watched != null) watched.removeListener(queueListener);
		watched = null;
		super.onInactive();
	}

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		List<Row> rows = new ArrayList<>();
		MusicQueue q = MusicPlayer.getQueue(a);
		watch(q);
		if ((q == null) || q.isEmpty()) {
			focusRow = -1;
			done.done(rows, 0);
			return;
		}
		List<MusicTrackItem> order = q.getPlayOrder();
		MusicTrackItem cur = MusicPlayer.getCurrentTrack(a.getMediaSessionCallback());
		if (cur == null) cur = q.getSavedCurrent();
		int n = Math.min(order.size(), 300);
		int playing = -1;
		for (int i = 0; i < n; i++) {
			MusicTrackItem t = order.get(i);
			boolean now = t.equals(cur);
			if (now) playing = i;
			// The channel, once known (it is looked up for songs queued without one); "YouTube" until then, as on the Music tab.
			String artist = t.getArtistName();
			if ((artist == null) && (t.getVideoId() != null)) artist = "YouTube";
			rows.add(new Row(t.getName(), artist, t, () -> MusicPlayer.playTrack(a, t, 0), null, null, now));
		}
		focusRow = playing;
		done.done(rows, order.size());
	}
}
