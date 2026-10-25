package me.aap.fermata.ui.home;

import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/** What a widget can ask of the Home tab it sits in. */
public interface HomeHost {
	MainActivityDelegate activity();

	HomePalette palette();

	boolean isEditing();

	/** Android Auto is showing the app: controls must be reachable with the steering wheel. */
	boolean isCar();

	/** The look a widget set to "Same as Home" has: {@link HomeWidgetView#STYLE_SOLID} and so on. */
	int defaultStyle();

	/** The widgets' options changed: write the layout. */
	void optionsChanged();

	/** Runs an action as if its floating button was tapped. */
	void run(Action action);

	/** Opens a tab by fragment id (out of fullscreen video first). */
	void open(int fragmentId);

	/** Whether Home animations (stack dots, entrances) are on. */
	boolean animate();

	/** Seconds between a stack turning to its next widget by itself, 0 when it does not. */
	int stackRotateSeconds();

	/** How opaque the widgets' cards are, 0 to 100 (Settings > Home > Widget look). */
	int cardAlpha();

	/**
	 * Glass cards (ui/view/Glass): draws Home's background behind {@code card} blurred, under {@code r}
	 * (in the card's coordinates), through the lens in {@code slot[0]}. False when it can't be drawn
	 * (below Android 12, the background not recorded yet); the card then shows the tint alone.
	 */
	default boolean drawGlassBehind(android.view.View card, android.graphics.Canvas c, android.graphics.RectF r,
																	float radius, float alpha, me.aap.fermata.ui.view.Glass.Look look,
																	me.aap.fermata.ui.view.GlassLens[] slot) {
		return false;
	}
}
