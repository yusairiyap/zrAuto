package me.aap.fermata.ui.home.widget;

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

	QueueWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.music_addon;
	}

	@Override
	protected int emptyRes() {
		return R.string.home_queue_empty;
	}

	@Override
	protected void onActive() {
		super.onActive();
		act().getMediaServiceBinder().addBroadcastListener(itemListener);
	}

	@Override
	protected void onInactive() {
		act().getMediaServiceBinder().removeBroadcastListener(itemListener);
		super.onInactive();
	}

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		List<Row> rows = new ArrayList<>();
		MusicQueue q = MusicPlayer.getQueue(a);
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
			String artist = t.getArtistName();
			rows.add(new Row(now ? "▶  " + t.getName() : t.getName(), (artist == null) ? null : artist, t,
					() -> MusicPlayer.playTrack(a, t, 0), null, null));
		}
		focusRow = playing;
		done.done(rows, order.size());
	}
}
