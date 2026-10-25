package me.aap.fermata.ui.view;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * The settings' top level as the list beside every settings page on a wide window: what Settings built (its categories, and the
 * buttons at its root that open pages of their own), and which of them the page on screen belongs to. Written by
 * {@code SettingsFragment}, read by {@link SettingsSplit}.
 */
public final class SettingsNav {
	private SettingsNav() {
	}

	/** One line of the list: a category (it has a {@code setId}) or a button at the settings' root. */
	public static final class Entry {
		public final String key;
		public final CharSequence title;
		@Nullable
		public final CharSequence subtitle;
		@DrawableRes
		public final int icon;
		/** The id of the category's preference set, 0 for a button. */
		public final int setId;
		@Nullable
		public final Runnable action;

		public Entry(@NonNull String key, @NonNull CharSequence title, @Nullable CharSequence subtitle, int icon, int setId,
								 @Nullable Runnable action) {
			this.key = key;
			this.title = title;
			this.subtitle = subtitle;
			this.icon = icon;
			this.setId = setId;
			this.action = action;
		}
	}

	private static List<Entry> entries = Collections.emptyList();
	@Nullable
	private static String selected;

	@NonNull
	public static List<Entry> entries() {
		return entries;
	}

	public static void publish(@NonNull List<Entry> list) {
		entries = list;
		SettingsSplit.entriesChanged();
	}

	public static void clear() {
		entries = Collections.emptyList();
		SettingsSplit.entriesChanged();
	}

	@Nullable
	public static String selected() {
		return selected;
	}

	/** The entry the page now showing belongs to. */
	public static void select(@Nullable String key) {
		selected = key;
		SettingsSplit.selectionChanged();
	}

	public static String categoryKey(int setId) {
		return "set:" + setId;
	}
}
