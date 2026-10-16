package me.aap.fermata.media.service;

import androidx.annotation.NonNull;

import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.utils.async.FutureSupplier;

/**
 * @author Andrey Pavlenko
 */
public interface MediaSessionCallbackAssistant {

	default void startVoiceAssistant(){}

	/**
	 * Plays an external item (a YouTube video, a Music tab track) in its own player from
	 * {@code pos}, for the media session resuming it -- see {@code MediaSessionCallback#resume()}.
	 * False if it can't be played here.
	 */
	default boolean playExternal(MediaLib.PlayableItem i, long pos) {
		return false;
	}

	/**
	 * The item {@code eng} plays is about to give way to another (a skip, the end of a track): the UI
	 * may take the picture through black meanwhile, as YouTube's video does.
	 */
	default void skipFadeStarted(me.aap.fermata.media.engine.MediaEngine eng) {
	}

	/**
	 * The queue ran out after {@code last} (Repeat off): something else may carry on -- YouTube's
	 * suggestions after a Music tab song. True if it does (it takes the session over once it plays).
	 */
	default boolean continueAfterQueueEnd(MediaLib.PlayableItem last) {
		return false;
	}

	@NonNull
	default FutureSupplier<MediaLib.PlayableItem> getPrevPlayable(Item i) {
		return i.getPrevPlayable();
	}

	@NonNull
	default FutureSupplier<MediaLib.PlayableItem> getNextPlayable(Item i) {
		return i.getNextPlayable();
	}
}
