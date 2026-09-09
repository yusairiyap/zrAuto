package me.aap.utils.ui.view;

/**
 * Implemented by a content view that wants to handle its own top/bottom safe-area inset instead
 * of the generic padding/margin handling applied to a plain {@code View} -- e.g. a WebView whose
 * page content isn't reachable through Android's own padding/margin mechanisms and instead needs
 * the inset pushed into the page itself.
 */
public interface ContentInsetConsumer {
	/**
	 * Called whenever the overlaid bars this content sits under/over change size, with their
	 * current heights in px. Implementations should no-op when neither value actually changed.
	 */
	void setContentInset(int top, int bottom);
}
