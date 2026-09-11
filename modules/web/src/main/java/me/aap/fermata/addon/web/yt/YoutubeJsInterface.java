package me.aap.fermata.addon.web.yt;

import me.aap.fermata.addon.web.FermataJsInterface;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.utils.async.Completable;
import me.aap.utils.async.Promise;
import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeJsInterface extends FermataJsInterface {
	public static final int JS_VIDEO_FOUND = JS_LAST + 1;
	public static final int JS_VIDEO_PLAYING = JS_LAST + 2;
	public static final int JS_VIDEO_PAUSED = JS_LAST + 3;
	public static final int JS_VIDEO_ENDED = JS_LAST + 4;
	public static final int JS_VIDEO_QUALITIES = JS_LAST + 5;
	public static final int JS_AD_SHOWING = JS_LAST + 6;
	public static final int JS_AD_ENDED = JS_LAST + 7;
	public static final int JS_CONTENT_PLAYING = JS_LAST + 8;
	public static final int JS_SKIP_PREV_NEXT = JS_LAST + 9;
	private final YoutubeMediaEngine engine;
	private Promise<String> result;

	public YoutubeJsInterface(FermataWebView webView, YoutubeMediaEngine engine) {
		super(webView);
		this.engine = engine;
	}

	Promise<String> getResultPromise() {
		if (result != null) result.cancel();
		return result = new Promise<>();
	}

	protected void handleEvent(int event, String data) {
		switch (event) {
			case JS_VIDEO_FOUND:
				Log.d("Video found");
				break;
			case JS_VIDEO_PLAYING:
				Log.d("Video playing");
				engine.playing(data);
				break;
			case JS_VIDEO_PAUSED:
				Log.d("Video paused");
				engine.paused();
				break;
			case JS_VIDEO_ENDED:
				Log.d("Video ended");
				engine.ended();
				break;
			case JS_VIDEO_QUALITIES:
				Log.d("Video qualities: ", data);
				setResult(data);
				break;
			case JS_AD_SHOWING:
				Log.d("Ad showing");
				engine.adShowing();
				break;
			case JS_AD_ENDED:
				Log.d("Ad ended");
				engine.adEnded();
				break;
			case JS_CONTENT_PLAYING:
				Log.d("Content playing");
				engine.contentPlaying();
				break;
			case JS_SKIP_PREV_NEXT:
				Log.d("Skip requested: ", data);
				engine.skipRequested("1".equals(data));
				break;
			default:
				super.handleEvent(event, data);
		}
	}

	private void setResult(String data) {
		Completable<String> r = result;
		result = null;
		if (r != null) r.complete(data);
		else Log.e("Unknown result recipient: ", data);
	}
}
