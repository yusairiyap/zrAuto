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
}
