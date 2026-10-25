package me.aap.fermata.media.pref;

import androidx.annotation.NonNull;

import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.function.Supplier;

import me.aap.utils.pref.PreferenceStore;

/**
 * @author Andrey Pavlenko
 */
public interface PlaylistPrefs extends BrowsableItemPrefs {
	Pref<Supplier<String>> PLAYLIST_NAME = Pref.s("NAME", "");
	Pref<Supplier<String[]>> PLAYLIST_ITEMS = Pref.sa("ITEMS", new String[0]);
	/** When items were last added to the playlist (epoch ms), 0 if never. */
	Pref<LongSupplier> PLAYLIST_ADDED_AT = Pref.l("ADDED_AT", 0).withInheritance(false);

	@NonNull
	PreferenceStore getPlaylistPreferenceStore();

	default String getPlaylistNamePref() {
		return getPlaylistPreferenceStore().getStringPref(PLAYLIST_NAME);
	}

	default void setPlaylistNamePref(String name) {
		getPlaylistPreferenceStore().applyStringPref(PLAYLIST_NAME, name);
	}

	default String[] getPlaylistItemsPref() {
		return getPlaylistPreferenceStore().getStringArrayPref(PLAYLIST_ITEMS);
	}

	default void setPlaylistItemsPref(String[] items) {
		getPlaylistPreferenceStore().applyStringArrayPref(PLAYLIST_ITEMS, items);
	}

	default long getPlaylistAddedAtPref() {
		return getPlaylistPreferenceStore().getLongPref(PLAYLIST_ADDED_AT);
	}

	default void setPlaylistAddedAtPref(long time) {
		getPlaylistPreferenceStore().applyLongPref(PLAYLIST_ADDED_AT, time);
	}

	@Override
	default Pref<BooleanSupplier> getTitleNamePrefKey() {
		return PlaylistsPrefs.PLAYLIST_TITLE_NAME;
	}

	@Override
	default Pref<BooleanSupplier> getTitleFileNamePrefKey() {
		return PlaylistsPrefs.PLAYLIST_TITLE_FILE_NAME;
	}
}
