package me.aap.fermata.media.lib;

import static java.util.Objects.requireNonNull;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.async.Completed.failed;
import static me.aap.utils.collection.CollectionUtils.mapToArray;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.media.engine.BitmapCache;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.MediaLib.Playlists;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.PlaylistPrefs;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.text.SharedTextBuilder;

/**
 * @author Andrey Pavlenko
 */
class DefaultPlaylist extends ItemContainer<PlayableItem> implements Playlist, PlaylistPrefs {
	private final int playlistId;
	private final SharedPreferenceStore playlistPrefStore;

	private DefaultPlaylist(String id, BrowsableItem parent, int playlistId) {
		super(id, parent, null);
		this.playlistId = playlistId;
		SharedPreferences prefs = getLib().getContext().getSharedPreferences("playlist_" + playlistId,
				Context.MODE_PRIVATE);
		playlistPrefStore = SharedPreferenceStore.create(prefs, getLib().getPrefs());
	}

	public static DefaultPlaylist create(String id, BrowsableItem parent, int playlistId, DefaultMediaLib lib) {
		synchronized (lib.cacheLock()) {
			Item i = lib.getFromCache(id);

			if (i != null) {
				DefaultPlaylist pl = (DefaultPlaylist) i;
				if (BuildConfig.D && !parent.equals(pl.getParent())) throw new AssertionError();
				if (BuildConfig.D && !id.equals(pl.getId())) throw new AssertionError();
				return pl;
			} else {
				return new DefaultPlaylist(id, parent, playlistId);
			}
		}
	}

	@Override
	protected FutureSupplier<String> buildTitle() {
		return completed(getName());
	}

	@Override
	protected FutureSupplier<String> buildSubtitle() {
		return getUnsortedChildren().main().map(l ->
				getLib().getContext().getResources().getString(R.string.browsable_subtitle, l.size()));
	}

	@NonNull
	@Override
	public String getName() {
		return getPlaylistNamePref();
	}

	@Override
	public FutureSupplier<Void> rename(CharSequence name) {
		String n = name.toString().trim();
		Context ctx = getLib().getContext();

		if (n.isEmpty() || (n.indexOf('/') != -1)) {
			String err = ctx.getResources().getString(R.string.err_invalid_playlist_name, n);
			return failed(new IllegalArgumentException(err));
		}

		if (n.equals(getName())) return completedVoid();

		return getParent().getUnsortedChildren().main().then(list -> {
			for (Item i : list) {
				if ((i != this) && (i instanceof Playlist) && n.equals(((Playlist) i).getName())) {
					String err = ctx.getResources().getString(R.string.err_playlist_exists, n);
					return failed(new IllegalArgumentException(err));
				}
			}

			setPlaylistNamePref(n);
			// buildTitle() reads the name pref, but the MediaDescription built from it is cached --
			// drop it so the list shows the new name.
			updateTitles();
			return completedVoid();
		});
	}

	public int getPlaylistId() {
		return playlistId;
	}

	@NonNull
	@Override
	public Playlists getParent() {
		return (Playlists) requireNonNull(super.getParent());
	}

	@NonNull
	@Override
	public BrowsableItemPrefs getPrefs() {
		return this;
	}

	@NonNull
	@Override
	public PreferenceStore getPlaylistPreferenceStore() {
		return playlistPrefStore;
	}

	@Override
	public Collection<ListenerRef<Listener>> getBroadcastEventListeners() {
		return getLib().getBroadcastEventListeners();
	}

	public FutureSupplier<List<Item>> listChildren() {
		return listChildren(getPlaylistPreferenceStore(), PLAYLIST_ITEMS);
	}

	@Override
	protected String getScheme() {
		return getId();
	}

	@Override
	public String toChildItemId(String id) {
		if (isChildItemId(id)) return id;
		SharedTextBuilder tb = SharedTextBuilder.get();
		return tb.append(getScheme()).append(id).releaseString();
	}

	@Override
	protected void saveChildren(List<PlayableItem> children) {
		setPlaylistItemsPref(mapToArray(children, PlayableItem::getOrigId, String[]::new));
		// The cover is a collage of the first items: have it rebuilt for the new contents.
		reset();
	}

	/**
	 * A 2x2 collage of the first four items' thumbnails (fewer than four: the first one's). Saved
	 * as an image keyed by those four thumbnails, so it's built once and rebuilt only when the
	 * first four items change.
	 */
	@NonNull
	@Override
	public FutureSupplier<Uri> getIconUri() {
		return getUnsortedChildren().then(children -> {
			List<PlayableItem> first = new ArrayList<>(4);
			for (Item i : children) {
				if (i instanceof PlayableItem pi) {
					first.add(pi);
					if (first.size() == 4) break;
				}
			}
			if (first.isEmpty()) return completedNull();

			List<Uri> icons = new ArrayList<>(4);
			return Async.forEach(pi -> pi.getIconUri().ifFail(err -> null).onSuccess(u -> {
				if (u != null) icons.add(u);
			}), first).then(v -> collage(icons));
		});
	}

	private FutureSupplier<Uri> collage(List<Uri> icons) {
		if (icons.size() < 4) return icons.isEmpty() ? completedNull() : completed(icons.get(0));

		StringBuilder key = new StringBuilder("zrauto-collage:/");
		for (Uri u : icons) key.append(u).append('\n');
		String k = key.append(".jpg").toString();
		BitmapCache bc = getLib().getBitmapCache();
		Uri existing = bc.getAddedImage(k);
		if (existing != null) return completed(existing);

		List<Bitmap> bitmaps = new ArrayList<>(4);
		return Async.forEach(u -> getLib().getBitmap(u.toString(), true, true).ifFail(err -> null)
				.onSuccess(bitmaps::add), icons).then(v -> {
			for (Bitmap b : bitmaps) {
				if (b == null) return completed(icons.get(0));
			}
			if (bitmaps.size() < 4) return completed(icons.get(0));
			return bc.addImage(k, () -> collage(bitmaps.get(0), bitmaps.get(1), bitmaps.get(2),
					bitmaps.get(3)));
		});
	}

	private static Bitmap collage(Bitmap... cells) {
		int cell = 0;
		for (Bitmap b : cells) cell = Math.max(cell, Math.min(b.getWidth(), b.getHeight()));
		cell = Math.max(64, Math.min(cell, 320));
		Bitmap out = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(out);
		Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

		for (int i = 0; i < 4; i++) {
			Bitmap b = cells[i];
			int w = b.getWidth();
			int h = b.getHeight();
			int s = Math.min(w, h);
			// Centre-cropped to a square, like the cards' own thumbnails.
			Rect src = new Rect((w - s) / 2, (h - s) / 2, (w - s) / 2 + s, (h - s) / 2 + s);
			int x = (i % 2) * cell;
			int y = (i / 2) * cell;
			c.drawBitmap(b, src, new Rect(x, y, x + cell, y + cell), paint);
		}

		return out;
	}

	/**
	 * Playlists can be shown sorted (by name, or shuffled) without changing their own order,
	 * which "Custom order" (no sorting, the default) shows and drag and drop edits.
	 */
	@Override
	public boolean sortChildrenEnabled() {
		return true;
	}

	@Override
	public int getSupportedSortOpts() {
		return BrowsableItemPrefs.SORT_MASK_NAME_RND;
	}

	/** Same as Favorites: newly added items go to the top of the playlist. */
	@Override
	protected boolean addToTop() {
		return true;
	}

	@Override
	protected void itemAdded(PlayableItem i) {
		getLib().getAtvInterface(a -> a.addProgram(i));
	}

	@Override
	protected void itemRemoved(PlayableItem i) {
		super.itemRemoved(i);
		getLib().getAtvInterface(a -> a.removeProgram(i));
	}
}