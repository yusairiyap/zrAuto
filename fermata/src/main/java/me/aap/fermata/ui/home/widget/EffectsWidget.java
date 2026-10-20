package me.aap.fermata.ui.home.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.EffectsControls;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.ChipFlowLayout;

/**
 * The sound effects of the player that plays (Android's equalizer, bass boost, virtualizer and
 * loudness; YouTube's own equalizer, bass, virtualizer and reverb): a row per effect with its
 * switch and, where it has one, a strength slider; the equalizer's bands as bars you can drag, and
 * a chip that steps through its presets. What fits the card is shown, rows first, the bars with
 * whatever room is left. With nothing playing, YouTube's are shown: their settings stay. The header
 * opens the full Audio effects screen.
 */
final class EffectsWidget extends HomeWidgetView {
	private static final long REFRESH_MS = 2500;
	private static final int[] KINDS = {EffectsControls.EQ, EffectsControls.BASS, EffectsControls.VIRT,
			EffectsControls.EXTRA};

	private LinearLayout column;
	private View headerRow;
	private Bars bars;
	private TextView status;
	private LinearLayout rows;
	/** The one line of switches a one-row card has. */
	private ChipFlowLayout chips;
	@Nullable
	private EffectsControls source;
	private String shownSignature = "";
	private final List<TextView> toggles = new ArrayList<>();
	private final List<TextView> stripToggles = new ArrayList<>();
	private final List<LinearLayout> rowViews = new ArrayList<>();
	private final List<SeekBar> sliders = new ArrayList<>();
	private final List<Integer> sliderKinds = new ArrayList<>();
	private final List<TextView> presetChips = new ArrayList<>();
	private boolean sliding;
	private final Runnable refresh = this::read;
	private final MediaSessionCallback.Listener listener = new MediaSessionCallback.Listener() {
		@Override
		public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
			post(EffectsWidget.this::read);
		}
	};

	EffectsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> host.run(Action.AUDIO_EFFECTS));
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		bars = new Bars(ctx);
		bars.setContentDescription(ctx.getString(R.string.action_audio_effects));
		bars.setListener(this::dragBand);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		blp.topMargin = px(8);
		column.addView(bars, blp);
		status = text(ROLE_SECONDARY, 12.5f, false);
		status.setSingleLine(true);
		status.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(22));
		slp.topMargin = px(4);
		column.addView(status, slp);
		rows = new LinearLayout(ctx);
		rows.setOrientation(LinearLayout.VERTICAL);
		column.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		chips = new ChipFlowLayout(ctx);
		column.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("bars", ctx.getString(R.string.home_opt_effects_bars), true));
		l.add(HomeOption.sw("switches", ctx.getString(R.string.home_opt_effects_switches), true));
		return l;
	}

	@Override
	protected void onStyleChanged() {
		if (bars == null) return;
		bars.setColors(color(ROLE_ACCENT), softFill());
		paintChips();
	}

	@Override
	protected void onBind() {
		arrange();
		read();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column == null) return;
		arrange();
		read();
	}

	@Override
	protected void onSized(int w, int h) {
		if (column != null) arrange();
	}

	/**
	 * Decides what the card's height allows: the rows (switch + slider) first, as many as fit, then
	 * the bars with the status line when all the rows fit and there is still room for them.
	 */
	private void arrange() {
		if ((column == null) || (heightPx() == 0)) return;
		boolean strip = getCellsH() == 1;
		boolean wantBars = optBool("bars", true);
		boolean wantRows = optBool("switches", true);
		headerRow.setVisibility(strip ? GONE : VISIBLE);
		chips.setVisibility(strip && wantRows ? VISIBLE : GONE);
		rows.setVisibility(!strip && wantRows ? VISIBLE : GONE);
		int avail = heightPx() - (strip ? 0 : px(34)) - px(10);
		int rowH = px(40);
		int n = rowViews.size();
		int barsMin = px(60) + px(26) + px(8);
		boolean showBars = false;
		int shownRows = 0;
		if (!strip) {
			if (wantRows && n > 0) {
				if (wantBars && (avail >= n * rowH + barsMin)) {
					shownRows = n;
					showBars = true;
				} else {
					shownRows = Math.max(1, Math.min(n, avail / rowH));
				}
			} else {
				showBars = wantBars;
			}
		}
		for (int i = 0; i < n; i++) rowViews.get(i).setVisibility(i < shownRows ? VISIBLE : GONE);
		bars.setVisibility(showBars ? VISIBLE : GONE);
		// The status line says how things are when there are no bars to show it, if there is room.
		status.setVisibility(!strip && (showBars || (!wantRows && !wantBars) ||
				(n == 0 && avail >= px(40))) ? VISIBLE : GONE);
	}

	@Override
	protected void onActive() {
		host.activity().getMediaSessionCallback().addBroadcastListener(listener);
		read();
	}

	@Override
	protected void onInactive() {
		host.activity().getMediaSessionCallback().removeBroadcastListener(listener);
		removeCallbacks(refresh);
	}

	/** The effects of what plays; YouTube's when nothing does, none for a player without any. */
	@Nullable
	private EffectsControls pick() {
		MediaEngine eng = host.activity().getMediaSessionCallback().getEngine();
		if (eng == null) return MusicPlayer.getYoutubeEffects();
		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) return MusicPlayer.getYoutubeEffects();
		if (!eng.supportsAudioEffects()) return null;
		try {
			return EffectsControls.of(eng.ensureAudioEffects());
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private void read() {
		if (column == null) return;
		removeCallbacks(refresh);
		EffectsControls src = pick();
		source = src;
		MediaEngine eng = host.activity().getMediaSessionCallback().getEngine();
		if (src == null) {
			if (!bars.isDragging()) bars.setLevels(null);
			status.setText((eng == null) ? R.string.home_effects_idle : R.string.home_effects_none);
			buildRows(null);
		} else {
			boolean on = src.isOn(EffectsControls.EQ);
			if (!bars.isDragging()) bars.setLevels(on ? src.bands() : null);
			String preset = src.preset(ctx);
			if (!src.has(EffectsControls.EQ)) status.setText(R.string.home_effects_on);
			else if (!on) status.setText(R.string.home_effects_off);
			else if (preset != null) status.setText(ctx.getString(R.string.home_effects_preset, preset));
			else status.setText(R.string.home_effects_on);
			buildRows(src);
		}
		if (isActive()) postDelayed(refresh, REFRESH_MS);
	}

	/** One band was dragged on the bars: set it (switching the equalizer on first). */
	private void dragBand(int band, float v) {
		EffectsControls s = source;
		if ((s == null) || !s.has(EffectsControls.EQ)) return;
		if (!s.isOn(EffectsControls.EQ)) {
			s.setOn(EffectsControls.EQ, true);
			paintChips();
		}
		s.setBand(band, v);
	}

	/** Builds the rows when the set of effects changed, else only paints their state. */
	private void buildRows(@Nullable EffectsControls src) {
		StringBuilder sig = new StringBuilder();
		if (src != null) {
			sig.append(System.identityHashCode(src.getClass()));
			for (int k : KINDS) {
				sig.append(src.has(k) ? '1' : '0');
				if (src.has(k)) sig.append(src.label(k)).append(src.hasLevel(k) ? 's' : 'n');
			}
		}
		String signature = sig.toString();
		if (!signature.equals(shownSignature)) {
			shownSignature = signature;
			rows.removeAllViews();
			chips.removeAllViews();
			toggles.clear();
			stripToggles.clear();
			rowViews.clear();
			sliders.clear();
			sliderKinds.clear();
			presetChips.clear();
			if (src != null) {
				for (int k : KINDS) {
					if (!src.has(k)) continue;
					final int kind = k;
					LinearLayout row = new LinearLayout(ctx);
					row.setGravity(Gravity.CENTER_VERTICAL);
					TextView t = switchChip(ctx.getString(src.label(k)), () -> toggle(kind));
					t.setTag(k);
					t.setMinWidth(px(96));
					toggles.add(t);
					row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34)));
					if (src.hasLevel(k)) {
						SeekBar sb = newSlider(kind);
						sliders.add(sb);
						sliderKinds.add(k);
						LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, px(34), 1f);
						slp.leftMargin = px(4);
						row.addView(sb, slp);
					} else if (k == EffectsControls.EQ) {
						TextView pc = switchChip("", () -> {
							EffectsControls s = source;
							if (s == null) return;
							if (!s.isOn(EffectsControls.EQ)) s.setOn(EffectsControls.EQ, true);
							s.nextPreset();
							read();
						});
						pc.setEllipsize(android.text.TextUtils.TruncateAt.END);
						presetChips.add(pc);
						LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, px(34), 1f);
						plp.leftMargin = px(8);
						row.addView(pc, plp);
					}
					LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40));
					rows.addView(row, rlp);
					rowViews.add(row);
					// The same switch again for the one-line card.
					TextView st = switchChip(ctx.getString(src.label(k)), () -> toggle(kind));
					st.setTag(k);
					stripToggles.add(st);
					chips.addView(st);
				}
			}
			arrange();
		}
		paintChips();
	}

	private void toggle(int kind) {
		EffectsControls s = source;
		if (s == null) return;
		s.setOn(kind, !s.isOn(kind));
		paintChips();
		read();
	}

	private SeekBar newSlider(int kind) {
		SeekBar sb = new SeekBar(ctx);
		sb.setMax(1000);
		sb.setPadding(px(10), 0, px(10), 0);
		sb.setOnTouchListener((v, e) -> {
			if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
			return false;
		});
		sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				if (!fromUser) return;
				EffectsControls s = source;
				if (s == null) return;
				// Moving a slider of an effect that is off switches it on, as one expects.
				if (!s.isOn(kind) && (progress > 0)) {
					s.setOn(kind, true);
					paintChips();
				}
				s.setLevel(kind, progress / 1000f);
			}

			@Override
			public void onStartTrackingTouch(SeekBar bar) {
				sliding = true;
			}

			@Override
			public void onStopTrackingTouch(SeekBar bar) {
				sliding = false;
			}
		});
		return sb;
	}

	private TextView switchChip(CharSequence label, Runnable click) {
		TextView t = new TextView(ctx);
		t.setText(label);
		t.setTextSize(12.5f);
		t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		t.setSingleLine(true);
		t.setGravity(Gravity.CENTER);
		t.setPadding(px(12), 0, px(12), 0);
		t.setMinHeight(px(34));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> click.run());
		return t;
	}

	/** Lit when on (the card's accent), faint when off; sliders in the accent too, dimmed when off. */
	private void paintChips() {
		EffectsControls src = source;
		List<TextView> all = new ArrayList<>(toggles);
		all.addAll(stripToggles);
		for (TextView t : all) {
			boolean on = (src != null) && src.isOn((Integer) t.getTag());
			t.setBackground(HomeUi.pressable(ripple(), on ? color(ROLE_ACCENT) : softFill(), px(17)));
			t.setTextColor(on ? color(ROLE_ON_ACCENT) : color(ROLE_PRIMARY));
		}
		for (int i = 0; i < sliders.size(); i++) {
			SeekBar sb = sliders.get(i);
			int kind = sliderKinds.get(i);
			boolean on = (src != null) && src.isOn(kind);
			int accent = on ? color(ROLE_ACCENT) : HomeUi.alpha(color(ROLE_SECONDARY), 0.7f);
			sb.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
			sb.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
			sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(softFill()));
			if (!sliding && (src != null)) sb.setProgress(Math.round(src.level(kind) * 1000));
		}
		String preset = (src == null) ? null : src.preset(ctx);
		for (TextView pc : presetChips) {
			pc.setText(ctx.getString(R.string.home_effects_preset_chip,
					(preset != null) ? preset : ctx.getString(R.string.eq_manual)));
			pc.setBackground(HomeUi.pressable(ripple(), softFill(), px(17)));
			pc.setTextColor(color(ROLE_PRIMARY));
		}
	}

	/** Rounded bars that glide to their new heights. */
	private static final class Bars extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private float[] shown = new float[0];
		private float[] target = new float[0];
		private boolean live;
		private int on;
		private int off;
		@Nullable
		private ValueAnimator anim;

		/** Called while a band is dragged: its index and its new height from 0 to 1. */
		interface Listener {
			void onBand(int band, float v);
		}

		@Nullable
		private Listener listener;
		private boolean dragging;

		Bars(Context ctx) {
			super(ctx);
		}

		void setListener(@Nullable Listener l) {
			listener = l;
		}

		boolean isDragging() {
			return dragging;
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if ((listener == null) || (shown.length == 0) || (getWidth() == 0) || (getHeight() == 0)) return false;
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					getParent().requestDisallowInterceptTouchEvent(true);
					dragging = true;
					if (anim != null) anim.cancel();
					target = shown.clone();
					live = true;
					drag(e);
				}
				case MotionEvent.ACTION_MOVE -> drag(e);
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false;
				default -> {
				}
			}
			return true;
		}

		private void drag(MotionEvent e) {
			int n = shown.length;
			int i = Math.max(0, Math.min(n - 1, (int) (e.getX() / getWidth() * n)));
			float v = 1f - e.getY() / getHeight();
			v = Math.max(0.08f, Math.min(1f, v));
			shown[i] = v;
			target[i] = v;
			invalidate();
			if (listener != null) listener.onBand(i, v);
		}

		void setColors(int on, int off) {
			this.on = on;
			this.off = off;
			invalidate();
		}

		/** Heights from 0 to 1, one per band; null when there is nothing to show (the bars rest). */
		void setLevels(@Nullable float[] levels) {
			float[] to = (levels != null) ? levels : rest(shown.length > 0 ? shown.length : 5);
			live = levels != null;
			if (java.util.Arrays.equals(to, target)) {
				invalidate();
				return;
			}
			if (shown.length != to.length) shown = rest(to.length);
			final float[] from = shown.clone();
			target = to;
			if (anim != null) anim.cancel();
			if (!isShown()) {
				shown = to.clone();
				invalidate();
				return;
			}
			ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
			a.setDuration(450);
			a.setInterpolator(new android.view.animation.DecelerateInterpolator());
			a.addUpdateListener(v -> {
				float f = (float) v.getAnimatedValue();
				for (int i = 0; i < shown.length; i++) shown[i] = from[i] + (target[i] - from[i]) * f;
				invalidate();
			});
			anim = a;
			a.start();
		}

		private static float[] rest(int n) {
			float[] r = new float[n];
			for (int i = 0; i < n; i++) r[i] = 0.18f + 0.1f * (float) Math.sin(i * 1.3);
			return r;
		}

		@Override
		protected void onDraw(Canvas c) {
			int n = shown.length;
			if (n == 0) shown = rest(5);
			n = shown.length;
			float w = getWidth();
			float h = getHeight();
			if ((w == 0) || (h == 0)) return;
			float gap = Math.max(4f, w / (n * 5f));
			float bw = (w - gap * (n - 1)) / n;
			paint.setColor(live ? on : off);
			for (int i = 0; i < n; i++) {
				float lv = Math.max(0.08f, Math.min(1f, shown[i]));
				float x = i * (bw + gap);
				rect.set(x, h - h * lv, x + bw, h);
				c.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
			}
		}
	}
}
