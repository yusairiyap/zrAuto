package me.aap.fermata.ui.home;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

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

	void setBlur(int blur) {
		if (this.blur == blur) return;
		this.blur = blur;
		if (running) refresh(true);
	}

	private void refresh(boolean force) {
		if (!running) return;
		PlayableItem pi = HomeArt.displayItem(a);
		if (!force && (pi == shown)) return;
		shown = pi;
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
				final int b = blur;
				App.get().getExecutor().submitTask(() -> {
					Bitmap soft = HomeBlur.blur(bm, Math.max(b, 40));
					Bitmap use = (soft != null) ? soft : bm;
					return new Object[]{use, HomeBackground.topColor(use)};
				}).main().onSuccess(r -> {
					if (gen != generation) return;
					target.onCover((Bitmap) r[0], (Integer) r[1]);
				});
			});
		});
	}
}
