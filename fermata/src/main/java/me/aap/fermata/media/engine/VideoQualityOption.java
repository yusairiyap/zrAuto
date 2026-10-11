package me.aap.fermata.media.engine;

import androidx.annotation.NonNull;

/**
 * One of the video qualities an engine offers (see {@link MediaEngine#getVideoQualityOptions()}),
 * as the Quality card shows it.
 */
public final class VideoQualityOption {
	/** The engine's own name for it, handed back to {@link MediaEngine#setVideoQuality}. */
	@NonNull
	public final String id;
	/** What the viewer reads: "Auto", "1080p", ... */
	@NonNull
	public final String label;
	/** Whether it's the one in use (or picked) now. */
	public final boolean selected;
	/** "Auto": the player picks, following the connection. */
	public final boolean auto;

	public VideoQualityOption(@NonNull String id, @NonNull String label, boolean selected,
														boolean auto) {
		this.id = id;
		this.label = label;
		this.selected = selected;
		this.auto = auto;
	}
}
