package me.aap.fermata.media.pref;

import androidx.annotation.Nullable;

import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.function.Supplier;

/**
 * @author Andrey Pavlenko
 */
public interface MediaLibPrefs extends BrowsableItemPrefs {
	Pref<BooleanSupplier> EXO_ENABLED = Pref.b("EXO_ENABLED", false).withInheritance(false);
	Pref<BooleanSupplier> VLC_ENABLED = Pref.b("VLC_ENABLED", false).withInheritance(false);
	int PLAY_MODE_VIDEO = 0;
	int PLAY_MODE_MUSIC = 1;
	/**
	 * How a tapped Favorites/Playlist item plays when nothing says otherwise: as video (the usual
	 * player) or as music (the Music tab). Settings &gt; Playback.
	 */
	Pref<IntSupplier> DEFAULT_PLAY_MODE =
			Pref.i("DEFAULT_PLAY_MODE", PLAY_MODE_VIDEO).withInheritance(false);
	/**
	 * Set while the last thing played was an external item (a YouTube video, a Music tab track):
	 * its {@link me.aap.fermata.media.lib.MediaLib.PlayableItem#getResumeId() resume id}, which
	 * takes precedence over {@link #LAST_PLAYED_ITEM} on the next start. Cleared as soon as a
	 * library item plays.
	 */
	Pref<Supplier<String>> RESUME_EXT_ITEM =
			Pref.s("RESUME_EXT_ITEM", (String) null).withInheritance(false);
	Pref<LongSupplier> RESUME_EXT_POS = Pref.l("RESUME_EXT_POS", 0).withInheritance(false);

	default boolean getPlayAsMusicByDefaultPref() {
		return getIntPref(DEFAULT_PLAY_MODE) == PLAY_MODE_MUSIC;
	}

	default boolean getExoEnabledPref() {
		return getBooleanPref(EXO_ENABLED);
	}

	default boolean getVlcEnabledPref() {
		return getBooleanPref(VLC_ENABLED);
	}

	@Nullable
	default String getResumeExtItemPref() {
		return getStringPref(RESUME_EXT_ITEM);
	}

	default long getResumeExtPosPref() {
		return getLongPref(RESUME_EXT_POS);
	}

	default void setResumeExtPref(@Nullable String id, long pos) {
		try (Edit e = editPreferenceStore()) {
			if (id == null) {
				e.removePref(RESUME_EXT_ITEM);
				e.removePref(RESUME_EXT_POS);
			} else {
				e.setStringPref(RESUME_EXT_ITEM, id);
				e.setLongPref(RESUME_EXT_POS, Math.max(pos, 0));
			}
		}
	}
}