package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.ytcaptions.YtStoryboard;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.fermata.ytdl.YtOffline;
import me.aap.utils.vfs.VirtualResource;

/**
 * The picture at the dragged position, floating over a seek bar while it is dragged (like
 * YouTube's): the control panel's, in the panel and over fullscreen video.
 * <ul>
 * <li>A YouTube video streamed: its storyboard ({@link YtStoryboard}), one player request per
 * video (kept for the session) and then only the sheets of small pictures the drag reaches, about
 * 20 to 40 KB each; the smaller pictures on mobile data.</li>
 * <li>A downloaded video or a video file on the phone: a frame read from the file
 * ({@link MediaMetadataRetriever}), no data at all, the latest position only.</li>
 * <li>Anything else (sound only, a web stream): just the time.</li>
 * </ul>
 */
public final class SeekPreview {
	private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "seek-preview");
		t.setDaemon(true);
		return t;
	});
	private static final Handler main = new Handler(Looper.getMainLooper());
	/** Storyboards by video id; a missing one is remembered as NONE. */
	private static final LruCache<String, Object> boards = new LruCache<>(16);
	private static final Object NONE = new Object();
	@Nullable
	private static SeekPreview current;

	private final MainActivityDelegate a;
	private final SeekBar bar;
	private final ViewGroup host;
	private final LinearLayout card;
	private final ImageView image;
	private final TextView time;
	private final int imgW;
	private final int imgH;
	@Nullable
	private final String videoId;
	@Nullable
	private final Object localSource;
	private int gen;
	// The local frame reader: one request at a time, only the latest position matters.
	@Nullable
	private volatile MediaMetadataRetriever retriever;
	private boolean reading;
	private long pendingMs = -1;
	private boolean closed;

	private SeekPreview(MainActivityDelegate a, SeekBar bar, ViewGroup host, @Nullable String videoId,
											@Nullable Object localSource) {
		this.a = a;
		this.bar = bar;
		this.host = host;
		this.videoId = videoId;
		this.localSource = localSource;
		Context ctx = host.getContext();
		float d = ctx.getResources().getDisplayMetrics().density;
		boolean picture = (videoId != null) || (localSource != null);
		imgW = picture ? Math.round(160 * d) : 0;
		imgH = picture ? Math.round(90 * d) : 0;

		card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setGravity(Gravity.CENTER_HORIZONTAL);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xE6000000);
		bg.setCornerRadius(10 * d);
		bg.setStroke(Math.max(1, Math.round(d)), 0x66FFFFFF);
		card.setBackground(bg);
		int pad = Math.round(3 * d);
		card.setPadding(pad, pad, pad, pad);
		card.setElevation(30 * d);
		card.setClipToOutline(true);
		image = new ImageView(ctx);
		image.setScaleType(ImageView.ScaleType.CENTER_CROP);
		GradientDrawable ib = new GradientDrawable();
		ib.setColor(0xFF202020);
		ib.setCornerRadius(8 * d);
		image.setBackground(ib);
		image.setClipToOutline(true);
		if (picture) card.addView(image, new LinearLayout.LayoutParams(imgW, imgH));
		time = new TextView(ctx);
		time.setTextColor(0xFFFFFFFF);
		time.setTypeface(Typeface.DEFAULT_BOLD);
		time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		time.setPadding(Math.round(8 * d), Math.round(2 * d), Math.round(8 * d), Math.round(2 * d));
		card.addView(time);
		card.setAlpha(0f);
		card.setVisibility(View.INVISIBLE);
	}

	/** The seek bar's drag started. */
	public static void start(SeekBar bar) {
		stop();
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(bar.getContext()).peek();
		if (a == null) return;
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) return;
		PlayableItem item = Action.getFavoritableItem(a);
		if (item == null) item = a.getMediaSessionCallback().getCurrentItem();
		String vid = YtDownloads.videoIdOf(item);
		Object local = null;
		String stream = null;

		if (vid != null) {
			YtDownloads.Entry e = YtDownloads.get().getEntry(vid);
			if (YtOffline.useLocal(vid)) {
				if ((e != null) && e.video) local = YtDownloads.get().getFile(vid);
			} else {
				MediaEngine eng = a.getMediaSessionCallback().getEngine();
				// In music mode there's no picture to look for.
				if ((eng != null) && (eng.getId() == MediaPrefs.MEDIA_ENG_YT) &&
						!me.aap.fermata.addon.music.MusicPlayer.isYoutubeAudioMode()) {
					stream = vid;
				}
			}
		} else if ((item != null) && item.isVideo() && !item.isStream()) {
			local = localSourceOf(item);
		}

		SeekPreview p = new SeekPreview(a, bar, host, stream, local);
		current = p;
		host.addView(p.card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		p.update(bar.getProgress());
		p.card.setVisibility(View.VISIBLE);
		p.card.animate().alpha(1f).setDuration(120).start();
	}

	/** The bar moved to {@code progress} (seconds) under the finger. */
	public static void move(SeekBar bar, int progress) {
		SeekPreview p = current;
		if ((p != null) && (p.bar == bar)) p.update(progress);
	}

	/** The drag ended. */
	public static void stop() {
		SeekPreview p = current;
		current = null;
		if (p == null) return;
		p.closed = true;
		p.gen++;
		View c = p.card;
		c.animate().alpha(0f).setDuration(150).withEndAction(() -> {
			ViewGroup parent = (ViewGroup) c.getParent();
			if (parent != null) parent.removeView(c);
		}).start();
		MediaMetadataRetriever r = p.retriever;
		p.retriever = null;
		if (r != null) worker.execute(() -> release(r));
	}

	@Nullable
	private static Object localSourceOf(PlayableItem item) {
		try {
			VirtualResource r = item.getResource();
			File f = r.getLocalFile();
			if ((f != null) && f.isFile()) return f;
			Uri u = r.getRid().toAndroidUri();
			if ((u != null) && "content".equals(u.getScheme())) return u;
		} catch (Exception ex) {
			DiagnosticLog.log("SEEK", "no local source", ex);
		}
		return null;
	}

	private void update(int sec) {
		long ms = sec * 1000L;
		time.setText(format(sec));
		place();
		if (videoId != null) showStoryboard(ms);
		else if (localSource != null) readLocal(ms);
	}

	/** Above the bar's thumb, kept inside the screen. */
	private void place() {
		int[] hl = new int[2];
		int[] bl = new int[2];
		host.getLocationInWindow(hl);
		bar.getLocationInWindow(bl);
		int max = Math.max(1, bar.getMax());
		int track = bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight();
		float thumbX = bl[0] - hl[0] + bar.getPaddingLeft() + track * (bar.getProgress() / (float) max);
		int w = card.getWidth();
		int h = card.getHeight();
		if ((w == 0) || (h == 0)) {
			// Not measured yet: once it is.
			card.post(() -> {
				if (!closed && (card.getWidth() > 0)) place();
			});
			return;
		}
		float margin = 8 * host.getResources().getDisplayMetrics().density;
		float x = Math.max(margin, Math.min(host.getWidth() - w - margin, thumbX - w / 2f));
		float y = bl[1] - hl[1] - h - margin;
		if (y < margin) y = bl[1] - hl[1] + bar.getHeight() + margin;
		card.setTranslationX(x);
		card.setTranslationY(y);
	}

	// ---- YouTube ----

	private void showStoryboard(long ms) {
		String vid = videoId;
		if (vid == null) return;
		Object b = boards.get(vid);
		if (b == NONE) return;
		if (b == null) {
			if (reading) return;
			reading = true;
			int g = gen;
			worker.execute(() -> {
				YtStoryboard sb = YtStoryboard.load(vid);
				main.post(() -> {
					boards.put(vid, (sb == null) ? NONE : sb);
					reading = false;
					if (!closed && (g == gen)) update(bar.getProgress());
				});
			});
			return;
		}

		YtStoryboard sb = (YtStoryboard) b;
		// The smaller pictures on mobile data: the sheets are a quarter of the size.
		int maxW = isMetered() ? 80 : 160;
		YtStoryboard.Level l = sb.pick(maxW);
		if (l == null) return;
		YtStoryboard.Frame f = sb.frameAt(l, ms, bar.getMax() * 1000L);
		if (f == null) return;
		int g = ++gen;
		a.getLib().getBitmap(f.url, true, false).main().onCompletion((sheet, err) -> {
			if (closed || (g != gen)) return;
			Bitmap bm = f.crop(sheet);
			if (bm != null) image.setImageBitmap(bm);
		});
	}

	private boolean isMetered() {
		try {
			android.net.ConnectivityManager cm =
					host.getContext().getSystemService(android.net.ConnectivityManager.class);
			return (cm != null) && cm.isActiveNetworkMetered();
		} catch (Exception ex) {
			return true;
		}
	}

	// ---- A file on the phone ----

	private void readLocal(long ms) {
		pendingMs = ms;
		if (reading) return;
		reading = true;
		long at = ms;
		pendingMs = -1;
		Object src = localSource;
		Context ctx = host.getContext();
		int w = imgW;
		int h = imgH;
		worker.execute(() -> {
			Bitmap bm = null;
			try {
				MediaMetadataRetriever r = retriever;
				if (r == null) {
					r = new MediaMetadataRetriever();
					if (src instanceof File file) r.setDataSource(file.getAbsolutePath());
					else r.setDataSource(ctx, (Uri) src);
					MediaMetadataRetriever opened = r;
					main.post(() -> {
						if (closed) worker.execute(() -> release(opened));
						else retriever = opened;
					});
				}
				long us = at * 1000L;
				int opt = MediaMetadataRetriever.OPTION_CLOSEST_SYNC;
				bm = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ?
						r.getScaledFrameAtTime(us, opt, w, h) : r.getFrameAtTime(us, opt);
			} catch (Exception ex) {
				DiagnosticLog.log("SEEK", "frame failed", ex);
			}
			Bitmap frame = bm;
			main.post(() -> {
				reading = false;
				if (closed) return;
				if (frame != null) image.setImageBitmap(frame);
				if (pendingMs >= 0) readLocal(pendingMs);
			});
		});
	}

	private static void release(MediaMetadataRetriever r) {
		try {
			r.release();
		} catch (Exception ignore) {
			// Gone either way.
		}
	}

	private static String format(int sec) {
		int h = sec / 3600;
		int m = (sec / 60) % 60;
		int s = sec % 60;
		return (h > 0) ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) :
				String.format(Locale.ROOT, "%d:%02d", m, s);
	}
}
