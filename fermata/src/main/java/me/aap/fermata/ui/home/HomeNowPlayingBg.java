package me.aap.fermata.ui.home;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.app.App;

/**
 * Follows what plays and hands the blurred cover to the Home background, which fades from one to
 * the next, the way the Music tab does its background. Runs only while Home is on screen.
 */
final class HomeNowPlayingBg {
	interface Target {
		/** The new background (null for none) and the colour at its top, for the status bar. */
		void onCover(@Nullable Bitmap blurred, int topColor);
	}

	private final MainActivityDelegate a;
	private final Target target;
	private boolean running;
	private int blur;
	private int generation;
	@Nullable
	private PlayableItem shown;
	/** The blur the picture now on screen was made with, -1 while there is none to keep. */
	private int shownBlur = -1;
	/** The cover the picture on screen was made from: the same one again is not blurred and faded in again. */
	@Nullable
	private Bitmap shownCover;
	private int shownCoverBlur = -1;
	private final FermataServiceUiBinder.Listener listener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			App.get().getHandler().post(() -> refresh(false));
		}
	};

	HomeNowPlayingBg(MainActivityDelegate a, Target target) {
		this.a = a;
		this.target = target;
	}

	void start(int blur) {
		this.blur = blur;
		if (!running) {
			running = true;
			a.getMediaServiceBinder().addBroadcastListener(listener);
		}
		refresh(true);
	}

	void stop() {
		if (!running) return;
		running = false;
		generation++;
		a.getMediaServiceBinder().removeBroadcastListener(listener);
	}

	boolean isRunning() {
		return running;
	}

	/** Hide cover (CoverHide) turned on or off: the picture is made again, colours only or as set. */
	void coverHideChanged() {
		if (running) refresh(false);
	}

	/** The blur asked for, or {@link #HIDDEN} while covers are hidden (which overrides it). */
	private int effectiveBlur() {
		return me.aap.fermata.ui.view.CoverHide.isOn() ? HIDDEN : blur;
	}

	private static final int HIDDEN = 1000;

	void setBlur(int blur) {
		if (this.blur == blur) return;
		this.blur = blur;
		if (running) refresh(false);
	}

	/** What is on screen is no longer what the background shows (its source changed): made again at the next start. */
	void forget() {
		shown = null;
		shownBlur = -1;
		shownCover = null;
	}

	private void refresh(boolean force) {
		if (!running) return;
		PlayableItem pi = HomeArt.displayItem(a);
		// Back on Home with the same song and blur: the picture is already there, it must not fade over itself.
		int eb = effectiveBlur();
		if (HomeArt.same(pi, shown) && (shownBlur == eb) && (pi != null)) {
			shown = pi;
			return;
		}
		shown = pi;
		shownBlur = eb;
		int gen = ++generation;
		if (pi == null) {
			target.onCover(null, 0);
			return;
		}
		pi.getMediaData().main().onCompletion((md, err) -> {
			if (gen != generation) return;
			HomeArt.cover(a, pi, md, () -> gen == generation, bm -> {
				if (gen != generation) return;
				if (bm == null) {
					target.onCover(null, 0);
					return;
				}
				final int b = eb;
				if ((bm == shownCover) && (shownCoverBlur == b)) return;
				App.get().getExecutor().submitTask(() -> {
					// Covers hidden: its colours only, whatever the blur setting says.
					Bitmap soft = (b == HIDDEN) ? me.aap.fermata.ui.view.CoverHide.veil(bm) : HomeBlur.blur(bm, b);
					Bitmap use = (soft != null) ? soft : bm;
					return new Object[]{use, HomeBackground.topColor(use)};
				}).main().onSuccess(r -> {
					if (gen != generation) return;
					shownCover = bm;
					shownCoverBlur = b;
					target.onCover((Bitmap) r[0], (Integer) r[1]);
				});
			});
		});
	}
}
