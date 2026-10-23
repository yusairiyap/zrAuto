package me.aap.fermata.ui.home;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import java.util.function.BooleanSupplier;

/**
 * A kind of widget: what the "Add widget" list shows, the sizes it can take (in grid units) and
 * how to make one. Registered in {@link HomeWidgets}.
 */
public final class HomeWidgetType {
	public interface Factory {
		HomeWidgetView create(HomeHost host, HomeWidgetType type);
	}

	public final String key;
	@StringRes
	public final int name;
	@StringRes
	public final int description;
	@DrawableRes
	public final int icon;
	/** The widget's colour: its icon, and the card in the Colour look. */
	public final int accent;
	public final int minW, minH, defW, defH, maxW, maxH;
	final Factory factory;
	final BooleanSupplier available;

	private HomeWidgetType(Builder b) {
		key = b.key;
		name = b.name;
		description = b.description;
		icon = b.icon;
		accent = b.accent;
		minW = b.minW;
		minH = b.minH;
		defW = b.defW;
		defH = b.defH;
		maxW = b.maxW;
		maxH = b.maxH;
		factory = b.factory;
		available = b.available;
	}

	/** False when the addon behind the widget is off: it can't be added, and shows a note. */
	public boolean isAvailable() {
		return available.getAsBoolean();
	}

	public HomeWidgetView create(HomeHost host) {
		return factory.create(host, this);
	}

	/**
	 * {@code key} is saved in the layout: never change it once a widget ships.
	 */
	public static Builder builder(String key, @StringRes int name, @StringRes int description,
																@DrawableRes int icon, int accent, Factory factory) {
		return new Builder(key, name, description, icon, accent, factory);
	}

	public static final class Builder {
		private final String key;
		private final int name, description, icon, accent;
		private final Factory factory;
		private int minW = 2, minH = 1, defW = 2, defH = 2, maxW = 4, maxH = 4;
		private BooleanSupplier available = () -> true;

		private Builder(String key, int name, int description, int icon, int accent, Factory factory) {
			this.key = key;
			this.name = name;
			this.description = description;
			this.icon = icon;
			this.accent = accent;
			this.factory = factory;
		}

		/** Smallest, default and largest size, in grid units. */
		public Builder sizes(int minW, int minH, int defW, int defH, int maxW, int maxH) {
			this.minW = minW;
			this.minH = minH;
			this.defW = defW;
			this.defH = defH;
			this.maxW = maxW;
			this.maxH = maxH;
			return this;
		}

		public Builder available(BooleanSupplier s) {
			available = s;
			return this;
		}

		public HomeWidgetType build() {
			return new HomeWidgetType(this);
		}
	}
}
