package me.aap.fermata.ui.home.widget;

import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceAddon;
import me.aap.fermata.addon.power.PowerUsageAddon;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.ui.home.HomeWidgetType;

/**
 * Every kind of widget Home can show, in the order the "Add widget" list offers them. To add one:
 * write a {@link me.aap.fermata.ui.home.HomeWidgetView} subclass next to the others, give it a name
 * and a description in {@code values/home.xml}, and list it here. The key is saved in the layout,
 * so it must never change once released.
 */
public final class HomeWidgets {
	private static final List<HomeWidgetType> ALL = List.of(
			HomeWidgetType.builder("media", R.string.home_w_media, R.string.home_w_media_d,
							R.drawable.music, 0xFFFF5C8A, MediaWidget::new)
					.sizes(2, 1, 4, 2, 8, 5).build(),
			HomeWidgetType.builder("pages", R.string.home_w_pages, R.string.home_w_pages_d,
							me.aap.utils.R.drawable.menu, 0xFF7C9CFF, PagesWidget::new)
					.sizes(2, 1, 4, 1, 8, 4).build(),
			HomeWidgetType.builder("favorites", R.string.home_w_favorites, R.string.home_w_favorites_d,
							R.drawable.favorite_filled, 0xFFFF6B8A, FavoritesWidget::new)
					.sizes(2, 1, 2, 2, 8, 6).build(),
			HomeWidgetType.builder("playlists", R.string.home_w_playlists, R.string.home_w_playlists_d,
							R.drawable.playlist, 0xFFB388FF, PlaylistsWidget::new)
					.sizes(2, 1, 2, 2, 8, 6).build(),
			HomeWidgetType.builder("bookmarks", R.string.home_w_bookmarks, R.string.home_w_bookmarks_d,
							R.drawable.bookmark_filled, 0xFFFFC857, BookmarksWidget::new)
					.sizes(2, 1, 2, 2, 8, 6).build(),
			HomeWidgetType.builder("fuel", R.string.home_w_fuel, R.string.home_w_fuel_d,
							R.drawable.fuel, 0xFFFF9F43, FuelWidget::new)
					.sizes(2, 1, 4, 1, 8, 3).available(FuelWidget::enabled).build(),
			HomeWidgetType.builder("data", R.string.home_w_data, R.string.home_w_data_d,
							R.drawable.data_usage, 0xFF5B8DEF, DataUsageWidget::new)
					.sizes(2, 1, 2, 2, 8, 4).available(DataUsageWidget::enabled).build(),
			HomeWidgetType.builder("power", R.string.home_w_power, R.string.home_w_power_d,
							R.drawable.power_usage, 0xFF1ED760, PowerWidget::new)
					.sizes(2, 1, 2, 2, 8, 4).available(PowerUsageAddon::isEnabled).build(),
			HomeWidgetType.builder("lyrics", R.string.home_w_lyrics, R.string.home_w_lyrics_d,
							R.drawable.lyrics, 0xFFE58BFF, LyricsWidget::new)
					.sizes(2, 1, 4, 2, 8, 5).available(MusicPlayer::hasYoutube).build(),
			HomeWidgetType.builder("youtube", R.string.home_w_youtube, R.string.home_w_youtube_d,
							R.drawable.youtube, 0xFFFF4E45, YoutubeWidget::new)
					.sizes(2, 1, 4, 2, 8, 6).available(MusicPlayer::hasYoutube).build(),
			HomeWidgetType.builder("aio", R.string.home_w_aio, R.string.home_w_aio_d,
							R.drawable.view_grid, 0xFF7FD6C8, AllInOneWidget::new)
					.sizes(2, 1, 4, 1, 8, 4).build(),
			HomeWidgetType.builder("ambience", R.string.home_w_ambience, R.string.home_w_ambience_d,
							R.drawable.ambience, 0xFF5B9BFF, AmbienceWidget::new)
					.sizes(2, 1, 4, 2, 8, 4).available(AmbienceAddon::isEnabled).build(),
			HomeWidgetType.builder("timer", R.string.home_w_timer, R.string.home_w_timer_d,
							R.drawable.timer, 0xFF8E7CFF, TimerWidget::new)
					.sizes(2, 1, 2, 2, 8, 3).build(),
			HomeWidgetType.builder("effects", R.string.home_w_effects, R.string.home_w_effects_d,
							R.drawable.equalizer, 0xFF4DD0E1, EffectsWidget::new)
					.sizes(2, 1, 2, 2, 8, 4).build(),
			HomeWidgetType.builder("overlay", R.string.home_w_overlay, R.string.home_w_overlay_d,
							R.drawable.clock, 0xFFFFB74D, InfoOverlayWidget::new)
					.sizes(2, 1, 4, 2, 8, 4).build());

	private HomeWidgets() {
	}

	public static List<HomeWidgetType> all() {
		return ALL;
	}

	@Nullable
	public static HomeWidgetType find(String key) {
		for (HomeWidgetType t : ALL) if (t.key.equals(key)) return t;
		return null;
	}
}
