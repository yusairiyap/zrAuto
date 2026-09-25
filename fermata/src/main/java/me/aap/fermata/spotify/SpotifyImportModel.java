package me.aap.fermata.spotify;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory state of a Spotify import session: the Spotify playlists the user pasted links for,
 * their tracks, and the YouTube video each track is matched to. Only ever touched on the main
 * thread -- the background fetch/search tasks hand their results back there before mutating it.
 */
public final class SpotifyImportModel {
	private SpotifyImportModel() {
	}

	/** A YouTube search result. */
	public static final class Video {
		public final String videoId;
		public final String title;
		@Nullable
		public final String channel;
		@Nullable
		public final String durationText;
		/** -1 if unknown. */
		public final long durationMs;

		public Video(String videoId, String title, @Nullable String channel,
								 @Nullable String durationText, long durationMs) {
			this.videoId = videoId;
			this.title = title;
			this.channel = channel;
			this.durationText = durationText;
			this.durationMs = durationMs;
		}

		/** 320x180, no letterboxing -- small enough to load a whole list of them. */
		public String thumbnailUrl() {
			return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
		}

		public String watchUrl() {
			return "https://m.youtube.com/watch?v=" + videoId;
		}
	}

	public static final class Track {
		public static final int MATCH_NONE = 0;
		public static final int MATCH_SEARCHING = 1;
		public static final int MATCH_FOUND = 2;
		public static final int MATCH_NOT_FOUND = 3;
		public static final int MATCH_FAILED = 4;

		public final String title;
		public final String artists;
		public final long durationMs;
		public boolean selected = true;
		public int matchState = MATCH_NONE;
		@Nullable
		public Video match;
		/** Results of the "Search more" lookup, null until the user asks for them. */
		@Nullable
		public List<Video> alternatives;
		/** The match was chosen by the user from the "Search more" list. */
		public boolean userPicked;
		public boolean altSearching;
		public boolean altExpanded;

		public Track(String title, String artists, long durationMs) {
			this.title = title;
			this.artists = artists;
			this.durationMs = durationMs;
		}

		public String searchQuery() {
			String a = firstArtist();
			return a.isEmpty() ? title : (a + " - " + title);
		}

		public String firstArtist() {
			int idx = artists.indexOf(',');
			return ((idx == -1) ? artists : artists.substring(0, idx)).trim();
		}

		public String displayName() {
			return artists.isEmpty() ? title : (title + " — " + artists);
		}
	}

	public static final class Playlist {
		public static final int STATE_LOADING = 0;
		public static final int STATE_LOADED = 1;
		public static final int STATE_FAILED = 2;

		/** The normalized reference, e.g. {@code playlist/37i9dQZF1DXcBWIGoYBM5M}. */
		public final String ref;
		public int state = STATE_LOADING;
		@Nullable
		public String error;
		public String name = "";
		/** Renamed by the user before importing: keep that name instead of Spotify's. */
		public boolean renamed;
		@Nullable
		public String owner;
		@Nullable
		public String coverUrl;
		public final List<Track> tracks = new ArrayList<>();
		/**
		 * Read through the official API (complete), rather than the embed page (first ~100 only).
		 */
		public boolean fullList;

		public Playlist(String ref) {
			this.ref = ref;
		}

		public int getSelectedCount() {
			int n = 0;
			for (Track t : tracks) if (t.selected) n++;
			return n;
		}

		public boolean isAllSelected() {
			return !tracks.isEmpty() && (getSelectedCount() == tracks.size());
		}

		public void setAllSelected(boolean selected) {
			for (Track t : tracks) t.selected = selected;
		}

		public boolean hasSelection() {
			if (state != STATE_LOADED) return false;
			for (Track t : tracks) if (t.selected) return true;
			return false;
		}
	}
}
