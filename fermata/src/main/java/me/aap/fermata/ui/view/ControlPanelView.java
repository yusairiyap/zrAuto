package me.aap.fermata.ui.view;

import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
import static me.aap.utils.ui.UiUtils.getTextAppearanceSize;
import static me.aap.utils.ui.UiUtils.isVisible;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DimenRes;
import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.view.GestureDetectorCompat;

import com.google.android.material.textview.MaterialTextView;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.AudioStreamInfo;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.pref.PlaybackControlPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.BasicPreferenceStore;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.GestureListener;
import me.aap.utils.ui.view.NavBarView;

/**
 * @author Andrey Pavlenko
 */
public class ControlPanelView extends ConstraintLayout
		implements MainActivityListener, PreferenceStore.Listener, OverlayMenu.SelectionHandler,
		GestureListener {
	private static final byte MASK_VISIBLE = 1;
	private static final byte MASK_VIDEO_MODE = 2;
	// Set while a screen with its own full player UI (the Music tab) is showing -- the panel stays
	// out of the way without forgetting whether it's otherwise meant to be visible.
	private static final byte MASK_SUPPRESSED = 4;
	// Set while a panel laid over the screen (the YouTube tab's search/Up next) runs down to the
	// bottom: the panel fades away and comes back, without forgetting whether it's meant to show.
	private static final byte MASK_COVERED = 8;
	/**
	 * How the panel is laid out (see {@link #applyLayout}): the two-line layout of fullscreen video
	 * (control_panel_view.xml, or control_panel_view2.xml for something not seekable), or, while
	 * browsing the tabs, the compact one with the playing item's art and title: a single line
	 * (control_panel_compact.xml), or on a narrow screen two lines (control_panel_compact2.xml).
	 */
	private static final int LAYOUT_CLASSIC = 0;
	private static final int LAYOUT_ROW = 1;
	private static final int LAYOUT_STACKED = 2;
	/** Narrower than this (dp), the compact single line no longer fits: two lines instead. */
	private static final int COMPACT_ROW_MIN_WIDTH = 720;
	/**
	 * The compact layouts' buttons, in dp: a box the size of the tool bar's buttons with the same
	 * padding around the glyph, so the icons read the same size as the tool bar's and the nav bar's.
	 * Play/pause gets a little less padding: a slightly bigger glyph, as the main action.
	 */
	private static final int COMPACT_BUTTON = 44;
	private static final int COMPACT_BUTTON_PAD = 11;
	private static final int COMPACT_PLAY_PAD = 8;
	/** The compact two-line layout's seek line, in dp. */
	private static final int COMPACT_SEEK_LINE = 36;
	@IdRes
	private static final int[] TRANSPORT_IDS = {R.id.control_prev, R.id.control_rw,
			R.id.control_play_pause, R.id.control_ff, R.id.control_next};
	/** The vertical padding control_panel_view.xml gives the transport buttons, in dp. */
	private static final int LAYOUT_BUTTON_PAD_V = 6;
	@IdRes
	private static final int[] ICON_IDS = {R.id.show_hide_bars_icon, R.id.control_menu_button_icon,
			R.id.control_prev, R.id.control_rw, R.id.control_play_pause, R.id.control_ff,
			R.id.control_next};
	/** Every tappable part of the panel, given the same pill-shaped press/focus highlight. */
	@IdRes
	private static final int[] BUTTON_IDS = {R.id.show_hide_bars, R.id.control_menu_button,
			R.id.control_prev, R.id.control_rw, R.id.control_play_pause, R.id.control_ff,
			R.id.control_next};
	/**
	 * Fullscreen video keeps the panel's old look, whatever the theme: a black scrim fading up into
	 * the video, white icons, edge to edge -- not a floating pill. See {@link #setVideoLook}.
	 */
	private static final int VIDEO_BG_COLOR = 0xFF000000;
	private static final int VIDEO_ICON_COLOR = 0xFFFFFFFF;
	/** The current-position/duration labels shown at the bottom corners of the seek bar. */
	@IdRes
	private static final int[] LABEL_IDS = {R.id.seek_time, R.id.seek_total};
	private final GestureDetectorCompat gestureDetector;
	private final ImageView showHideBars;
	/** The playing item's art and title, shown by the compact layouts only. */
	private final ImageView art;
	private final View info;
	private final TextView title;
	private final TextView subtitle;
	/** What {@link #art} shows now: the item, and its bitmap or uri (null: the placeholder). */
	@Nullable
	private PlayableItem artItem;
	@Nullable
	private Object artKey;
	/** The art uri being loaded for {@link #artItem} ("": its icon uri being looked up), or null. */
	@Nullable
	private String artLoading;
	private int layoutMode = -1;
	/** Whether the current layout shows {@link #art} and the title, see {@link #applyLayout}. */
	private boolean showsNowPlaying;
	private final SparseArray<ConstraintSet> layouts = new SparseArray<>();
	@DimenRes
	private final int size;
	@StyleRes
	private final int textAppearance;
	private PlaybackControlPrefs prefs;
	private HideTimer hideTimer;
	private byte mask;
	private View gestureSource;
	private TextView playbackTimer;
	private long scrollStamp;
	/**
	 * The nav bar's own icon color: the panel no longer has a background of its own, it's drawn as
	 * a row of the same floating pill as the nav bar ({@link FloatingBarsView}), in the nav bar's
	 * background color, so its icons and labels use the nav bar's tint to match.
	 */
	private final int iconColor;
	/** The padding the layout gave the panel; the pill look adds {@link #pillPadH}/{@link #pillPadTop}. */
	private final int basePadTop;
	private final int basePadBottom;
	private final int pillPadH;
	private final int pillPadTop;
	private boolean videoLook;
	/** The pill look's margins (see MainActivityDelegate#enableFloatingBars) while in the video look. */
	@Nullable
	private int[] pillMargins;
	/** The video view whose middle buttons/title (see {@link VideoControlsOverlay}) were shown last. */
	@Nullable
	private VideoView controlsHost;
	/**
	 * A double tap seek streak: until this time (uptime), every further tap on the video seeks
	 * again instead of starting a new gesture, like YouTube's tap-tap-tap for +10, +20, +30.
	 */
	private long seekStreakUntil;
	private boolean seekStreakForward;
	private int seekStreakSeconds;
	/** Set while the touch going on now is one of a seek streak's taps, swallowed whole. */
	private boolean seekStreakTouch;
	/**
	 * Seconds the streak's taps have asked for so far, not applied yet (negative: back). A streak
	 * makes one seek, once the taps stop (as YouTube does): a seek per tap made local engines
	 * (ExoPlayer and the like) seek over and over while still busy with the last one.
	 */
	private int pendingSeekSec;
	private final Runnable applySeekTask = this::applyPendingSeek;
	/** How long after the last tap the streak's seek is made. */
	private static final long SEEK_APPLY_DELAY_MS = 400L;
	/** Seconds a double tap (and each further tap of its streak) seeks. */
	private static final int DOUBLE_TAP_SEEK_SEC = 10;
	/** How long after a seek tap another tap still counts as part of the streak. */
	private static final long SEEK_STREAK_MS = 700L;

	public ControlPanelView(Context context, AttributeSet attrs) {
		super(context, attrs, R.attr.appControlPanelStyle);
		gestureDetector = new GestureDetectorCompat(context, this);
		inflate(context, R.layout.control_panel_view, this);

		TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.ControlPanelView,
				R.attr.appControlPanelStyle, R.style.AppTheme_ControlPanelStyle);
		size = ta.getLayoutDimension(R.styleable.ControlPanelView_size, 0);
		textAppearance = ta.getResourceId(R.styleable.ControlPanelView_textAppearance, 0);
		ta.recycle();

		// No background (and so no elevation shadow of its own): FloatingBarsView paints the pill
		// behind it, shared with the nav bar when that's at the bottom.
		setBackground(null);
		iconColor = NavBarView.resolveStyleColors(context)[0];
		setIconTint(iconColor);
		setLabelColor(iconColor);
		basePadTop = getPaddingTop();
		basePadBottom = getPaddingBottom();
		// Keeps the corner buttons (show/hide bars, menu) and their labels as clear of the pill's
		// rounded edge as the transport buttons below them are.
		pillPadH = toIntPx(context, 14);
		pillPadTop = toIntPx(context, 6);
		applyLookPadding();
		for (int id : BUTTON_IDS) {
			View b = findViewById(id);
			if (b != null) b.setBackgroundResource(R.drawable.pill_focusable_bg);
		}

		MainActivityDelegate a = getActivity();
		a.addBroadcastListener(this, ACTIVITY_DESTROY);
		a.getPrefs().addBroadcastListener(this);

		ViewGroup g = findViewById(R.id.show_hide_bars);
		showHideBars = (ImageView) g.getChildAt(0);
		g.setOnClickListener(this::showHideBars);
		g = findViewById(R.id.control_menu_button);
		g.setOnClickListener(this::showMenu);
		setShowHideBarsIcon(a);

		art = findViewById(R.id.control_art);
		info = findViewById(R.id.control_info);
		title = findViewById(R.id.control_title);
		subtitle = findViewById(R.id.control_subtitle);
		// Rounded corners: the art is clipped to its background's outline.
		GradientDrawable artBg = new GradientDrawable();
		artBg.setCornerRadius(toIntPx(context, 8));
		artBg.setColor((iconColor & 0x00FFFFFF) | 0x26000000);
		art.setBackground(artBg);
		art.setClipToOutline(true);
		setInfoColor(iconColor);
		applyLayout();
	}

	/**
	 * Builds a vertical scrim gradient that fades between {@code color} (opaque) and the same
	 * RGB at alpha 0 (rather than a fully transparent black), to avoid a black-fringing artifact
	 * as the alpha ramps down.
	 */
	public static GradientDrawable buildScrimGradient(int color, boolean fadeTowardBottom) {
		int transparent = color & 0x00FFFFFF;
		int[] stops = fadeTowardBottom ? new int[]{transparent, color} : new int[]{color, transparent};
		return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops);
	}

	public boolean isVideoLook() {
		return videoLook;
	}

	/**
	 * Switches between the floating-pill look (no background of its own, the nav bar's colors,
	 * inset from the screen edges) and the fullscreen-video look (black scrim, white icons, edge to
	 * edge). FloatingBarsView leaves the panel out of the pill while it's in the video look.
	 */
	private void setVideoLook(boolean video) {
		if (video == videoLook) return;
		videoLook = video;
		setBackground(video ? buildScrimGradient(VIDEO_BG_COLOR, true) : null);
		int c = labelColor();
		setIconTint(c);
		setLabelColor(c);
		setInfoColor(c);

		if (getLayoutParams() instanceof ConstraintLayout.LayoutParams lp) {
			if (video) {
				pillMargins = new int[]{lp.leftMargin, lp.rightMargin, lp.getMarginStart(),
						lp.getMarginEnd(), lp.bottomMargin, lp.goneBottomMargin, lp.goneStartMargin,
						lp.goneEndMargin};
				lp.leftMargin = lp.rightMargin = lp.bottomMargin = 0;
				lp.setMarginStart(0);
				lp.setMarginEnd(0);
				lp.goneBottomMargin = lp.goneStartMargin = lp.goneEndMargin = 0;
			} else if (pillMargins != null) {
				int[] m = pillMargins;
				lp.leftMargin = m[0];
				lp.rightMargin = m[1];
				lp.setMarginStart(m[2]);
				lp.setMarginEnd(m[3]);
				lp.bottomMargin = m[4];
				lp.goneBottomMargin = m[5];
				lp.goneStartMargin = m[6];
				lp.goneEndMargin = m[7];
			}
			setLayoutParams(lp);
		}

		applyLookPadding();
		applyLayout();
		applyFabVideoLook(video);
	}

	/**
	 * Arranges the panel for what it shows now: fullscreen video keeps its two-line layout as it
	 * was, the tabs get the compact one (see {@link #LAYOUT_CLASSIC}). Only the constraints are
	 * swapped: which views show stays up to the panel and FermataServiceUiBinder, so the layouts
	 * are told to leave visibility alone.
	 */
	void applyLayout() {
		boolean seek = findViewById(R.id.seek_bar).isEnabled();
		int mode = wantedLayout(seek, getWidth());
		layoutMode = mode;
		int res;
		if (mode == LAYOUT_CLASSIC) res = seek ? R.layout.control_panel_view : R.layout.control_panel_view2;
		else if (mode == LAYOUT_STACKED) res = R.layout.control_panel_compact2;
		else res = R.layout.control_panel_compact;
		getConstraints(res).applyTo(this);

		// The art and title on the single line, and on the car screen's two lines too; on a phone's
		// two lines they left the buttons too cramped.
		boolean nowPlaying = (mode == LAYOUT_ROW) || ((mode == LAYOUT_STACKED) && isOnCar());
		showsNowPlaying = nowPlaying;
		art.setVisibility(nowPlaying ? VISIBLE : GONE);
		info.setVisibility(nowPlaying ? VISIBLE : GONE);
		applyFocusOrder(mode, seek);
		applyTransportButtons();
		if (nowPlaying) syncNowPlaying();
		computeSize();
	}

	/** On the car's screen: Android Auto, or the phone mirrored to it. */
	private boolean isOnCar() {
		return getActivity().getAppActivity().isCarActivity()
				|| me.aap.fermata.FermataApplication.get().isMirroringMode();
	}

	private int wantedLayout(boolean seek, int width) {
		if (videoLook) return LAYOUT_CLASSIC;
		// Nothing to seek leaves no seek bar to put on a line of its own: a single line fits.
		if (!seek) return LAYOUT_ROW;
		if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
		return (width < toIntPx(getContext(), COMPACT_ROW_MIN_WIDTH)) ? LAYOUT_STACKED : LAYOUT_ROW;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		if ((w == oldw) || (layoutMode == LAYOUT_CLASSIC)) return;
		boolean seek = findViewById(R.id.seek_bar).isEnabled();
		// Not from inside the layout pass: posted, see CLAUDE.md on layout callbacks.
		if (wantedLayout(seek, w) != layoutMode) post(this::applyLayout);
	}

	private ConstraintSet getConstraints(@LayoutRes int layout) {
		ConstraintSet cs = layouts.get(layout);
		if (cs != null) return cs;
		Context ctx = getContext();
		ConstraintLayout l = new ConstraintLayout(ctx);
		inflate(ctx, layout, l);
		cs = new ConstraintSet();
		cs.clone(l);
		for (int id : cs.getKnownIds()) cs.setVisibilityMode(id, ConstraintSet.VISIBILITY_MODE_IGNORE);
		layouts.put(layout, cs);
		return cs;
	}

	/** D-pad left/right wrap around within each line. */
	private void applyFocusOrder(int mode, boolean seek) {
		View showHide = findViewById(R.id.show_hide_bars);
		View menu = findViewById(R.id.control_menu_button);
		View prev = findViewById(R.id.control_prev);
		View next = findViewById(R.id.control_next);

		if (mode == LAYOUT_ROW) {
			prev.setNextFocusLeftId(R.id.control_menu_button);
			menu.setNextFocusRightId(R.id.control_prev);
			showHide.setNextFocusLeftId(R.id.control_next);
			next.setNextFocusRightId(R.id.show_hide_bars);
		} else if ((mode == LAYOUT_STACKED) || seek) {
			prev.setNextFocusLeftId(R.id.control_next);
			showHide.setNextFocusLeftId(R.id.control_menu_button);
			next.setNextFocusRightId(R.id.control_prev);
			menu.setNextFocusRightId(R.id.show_hide_bars);
		} else {
			prev.setNextFocusLeftId(R.id.show_hide_bars);
			showHide.setNextFocusLeftId(R.id.control_menu_button);
			next.setNextFocusRightId(R.id.control_menu_button);
			menu.setNextFocusRightId(R.id.show_hide_bars);
		}
	}

	/**
	 * Over fullscreen video the floating buttons look like the round buttons in the middle of the
	 * picture (see VideoControlsOverlay), as separate buttons or as one pill: see FabPillView.
	 */
	private void applyFabVideoLook(boolean video) {
		FabPillView p = getActivity().getFabPill();
		if (p != null) p.setVideoLook(video);
	}

	/**
	 * Fullscreen video has previous/play-pause/next in the middle of the picture and seeks with a
	 * double tap (see {@link VideoControlsOverlay}), so the panel leaves out its whole row of
	 * transport buttons there, keeping the seek bar line only (see {@link #setSize} for the room left
	 * under it); outside fullscreen they are back as before. Rewind/fast forward only for
	 * something seekable, as {@code FermataServiceUiBinder} shows them (the seek bar is enabled
	 * exactly then).
	 */
	private void applyTransportButtons() {
		for (int id : new int[]{R.id.control_prev, R.id.control_play_pause, R.id.control_next}) {
			View v = findViewById(id);
			if (v != null) v.setVisibility(videoLook ? GONE : VISIBLE);
		}
		boolean seek = !videoLook && findViewById(R.id.seek_bar).isEnabled();
		View rw = findViewById(R.id.control_rw);
		View ff = findViewById(R.id.control_ff);
		if (rw != null) rw.setVisibility(seek ? VISIBLE : GONE);
		if (ff != null) ff.setVisibility(seek ? VISIBLE : GONE);
	}

	private void applyLookPadding() {
		if (videoLook) setPadding(0, basePadTop, 0, basePadBottom);
		else setPadding(pillPadH, pillPadTop, pillPadH, pillPadTop);
	}

	private int labelColor() {
		return videoLook ? VIDEO_ICON_COLOR : iconColor;
	}

	/** Retints the transport/menu icons, overriding the tint the theme applied at inflate time. */
	private void setIconTint(int color) {
		ColorStateList tint = ColorStateList.valueOf(color);
		for (int id : ICON_IDS) {
			ImageView icon = findViewById(id);
			if (icon != null) icon.setImageTintList(tint);
		}
	}

	/** The compact layouts' title (and a dimmer subtitle) in the icons' color. */
	private void setInfoColor(int color) {
		title.setTextColor(color);
		subtitle.setTextColor((color & 0x00FFFFFF) | 0xB3000000);
	}

	/** Recolors the position/duration labels, overriding the theme's textColorPrimary. */
	private void setLabelColor(int color) {
		for (int id : LABEL_IDS) {
			TextView label = findViewById(id);
			if (label != null) label.setTextColor(color);
		}
	}

	@Nullable
	@Override
	protected Parcelable onSaveInstanceState() {
		Parcelable parentState = super.onSaveInstanceState();
		Bundle b = new Bundle();
		b.putByte("MASK", (byte) (mask & ~(MASK_SUPPRESSED | MASK_COVERED)));
		b.putParcelable("PARENT", parentState);
		return b;
	}

	@Override
	protected void onRestoreInstanceState(Parcelable st) {
		if (st instanceof Bundle b) {
			super.onRestoreInstanceState(b.getParcelable("PARENT"));
			mask = b.getByte("MASK");
			if (mask != MASK_VISIBLE) super.setVisibility(GONE);
		}
	}

	public void bind(FermataServiceUiBinder b) {
		computeSize();
		prefs = b.getMediaSessionCallback().getPlaybackControlPrefs();
		b.bindControlPanel(this);
		b.bindPrevButton(findViewById(R.id.control_prev));
		b.bindRwButton(findViewById(R.id.control_rw));
		b.bindPlayPauseButton(findViewById(R.id.control_play_pause));
		b.bindFfButton(findViewById(R.id.control_ff));
		b.bindNextButton(findViewById(R.id.control_next));
		b.bindProgressBar(findViewById(R.id.seek_bar));
		b.bindProgressTime(findViewById(R.id.seek_time));
		b.bindProgressTotal(findViewById(R.id.seek_total));
		b.bound();
	}

	void computeSize() {
		MainActivityDelegate a = getActivity();
		setSize(a.getPrefs().getControlPanelSizePref(a));
	}

	private void setSize(float scale) {
		if (getLayoutParams() == null) return; // Not attached yet: bind() sizes it.
		if (layoutMode != LAYOUT_CLASSIC) {
			setCompactSize(scale);
			return;
		}
		Context ctx = getContext();
		TextView seekTime = findViewById(R.id.seek_time);
		TextView seekTotal = findViewById(R.id.seek_total);
		float textSize = getTextAppearanceSize(ctx, textAppearance) * scale;
		int textPad = seekTime.getPaddingTop() + seekTime.getPaddingBottom();
		int pad = 2 * toIntPx(ctx, 4) + textPad;
		int iconSize = (int) (textSize + pad);
		int panelSize = (int) (size * scale);
		int buttonSize = (int) (panelSize - textSize - pad);
		ControlPanelSeekView seek = findViewById(R.id.seek_bar);

		// The layout gives the icons a fixed dp padding, so the bigger the panel gets the more they
		// grow into each other -- scale it with the panel instead, and give a bit more of it than
		// the layout does.
		int btnPadV = Math.min(toIntPx(ctx, Math.round(8 * scale)), Math.max(0, buttonSize / 4));
		int cornerPad = toIntPx(ctx, Math.round(5 * scale));
		// The glyphs are drawn fitCenter inside their box, so padding alone would shrink them.
		// Grow each box (and the panel with it) by exactly the padding added on top of what the
		// layout already had, so the extra room lands around the icons and they stay their old size.
		int growButtons = 2 * Math.max(0, btnPadV - toIntPx(ctx, LAYOUT_BUTTON_PAD_V));
		int growCorners = 2 * cornerPad;
		buttonSize += growButtons;
		iconSize += growCorners;
		panelSize += growButtons + growCorners;
		// On a narrow screen each transport button only gets a fifth of the panel width, where the
		// horizontal padding could become the limiting dimension and shrink the glyph rather than
		// just space it out -- cap it so the height always stays the limiting one.
		int panelWidth = getWidth();
		if (panelWidth <= 0) panelWidth = getResources().getDisplayMetrics().widthPixels;
		int btnPadH = Math.min(toIntPx(ctx, Math.round(20 * scale)),
				Math.max(0, (panelWidth / 5 - (buttonSize - 2 * btnPadV)) / 2));
		setIconPadding(btnPadH, btnPadV, cornerPad);

		if (seek.isEnabled()) {
			setHeight(seek, iconSize);
			setSize(R.id.show_hide_bars_icon, iconSize);
			setSize(R.id.control_menu_button_icon, iconSize);
			seTextAppearance(seekTime, textSize);
			seTextAppearance(seekTotal, textSize);
			setHeight(R.id.control_prev, buttonSize);
			setHeight(R.id.control_rw, buttonSize);
			setHeight(R.id.control_play_pause, buttonSize);
			setHeight(R.id.control_ff, buttonSize);
		} else {
			panelSize = buttonSize;
			setSize(R.id.show_hide_bars_icon, buttonSize);
			setSize(R.id.control_menu_button_icon, buttonSize);
			setHeight(R.id.control_prev, buttonSize);
			setHeight(R.id.control_play_pause, buttonSize);
		}

		setHeight(R.id.control_next, buttonSize);
		// Fullscreen: no button row, the seek bar line only, with some room left under it so the
		// slider stays clear of the system's gesture area at the bottom edge.
		if (videoLook) panelSize = (seek.isEnabled() ? iconSize : buttonSize) + videoBottomGap();
		getLayoutParams().height = panelSize + (videoLook ? 0 : pillPadTop);
	}

	/**
	 * The compact layouts: square buttons the size of the tool bar's (see {@link #COMPACT_BUTTON}),
	 * the art as tall as them, and the seek bar on their line or, stacked, on a slimmer line under it.
	 */
	private void setCompactSize(float scale) {
		Context ctx = getContext();
		boolean stacked = layoutMode == LAYOUT_STACKED;
		float textSize = getTextAppearanceSize(ctx, textAppearance) * scale;
		int box = toIntPx(ctx, Math.round(COMPACT_BUTTON * scale));
		int pad = toIntPx(ctx, Math.round(COMPACT_BUTTON_PAD * scale));
		int seekLine = stacked ? toIntPx(ctx, Math.round(COMPACT_SEEK_LINE * scale)) : box;
		int cornerPad = stacked ? toIntPx(ctx, Math.round(8 * scale)) : pad;

		for (int id : TRANSPORT_IDS) {
			setSize(id, box);
			setPadding(id, pad, pad);
		}
		int playPad = toIntPx(ctx, Math.round(COMPACT_PLAY_PAD * scale));
		setPadding(R.id.control_play_pause, playPad, playPad);

		setSize(R.id.show_hide_bars_icon, seekLine);
		setSize(R.id.control_menu_button_icon, seekLine);
		setPadding(R.id.show_hide_bars_icon, cornerPad, cornerPad);
		setPadding(R.id.control_menu_button_icon, cornerPad, cornerPad);
		setHeight(R.id.seek_bar, seekLine);
		seTextAppearance(findViewById(R.id.seek_time), textSize * 0.85f);
		seTextAppearance(findViewById(R.id.seek_total), textSize * 0.85f);

		setSize(R.id.control_art, box - toIntPx(ctx, 4));
		title.setTextSize(COMPLEX_UNIT_PX, textSize * 0.9f);
		subtitle.setTextSize(COMPLEX_UNIT_PX, textSize * 0.75f);
		updateArtPadding();

		int content = stacked ? box + seekLine : box;
		getLayoutParams().height = content + getPaddingTop() + getPaddingBottom();
		requestLayout();
	}

	/** The room under the fullscreen seek bar: the gesture area's height, and never under 28dp. */
	private int videoBottomGap() {
		int gap = toIntPx(getContext(), 28);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			android.view.WindowInsets wi = getRootWindowInsets();
			if (wi != null) gap = Math.max(gap, wi.getMandatorySystemGestureInsets().bottom);
		}
		return gap;
	}

	private void setIconPadding(int btnPadH, int btnPadV, int cornerPad) {
		setPadding(R.id.control_prev, btnPadH, btnPadV);
		setPadding(R.id.control_rw, btnPadH, btnPadV);
		setPadding(R.id.control_play_pause, btnPadH, btnPadV);
		setPadding(R.id.control_ff, btnPadH, btnPadV);
		setPadding(R.id.control_next, btnPadH, btnPadV);
		setPadding(R.id.show_hide_bars_icon, cornerPad, cornerPad);
		setPadding(R.id.control_menu_button_icon, cornerPad, cornerPad);
	}

	private void setPadding(@IdRes int id, int h, int v) {
		View b = findViewById(id);
		if (b != null) b.setPadding(h, v, h, v);
	}

	private void seTextAppearance(TextView t, float size) {
		t.setTextAppearance(textAppearance);
		t.setTextSize(COMPLEX_UNIT_PX, size);
		// setTextAppearance() above carries its own android:textColor (the theme's normal
		// textColorPrimary), silently overwriting the constructor's setLabelColor(iconColor)
		// every time this runs (on bind, and again on every control-panel-size change) -- which is
		// why seek_time/seek_total kept showing the theme's own color instead of matching the icons.
		t.setTextColor(labelColor());
	}

	private void setSize(@IdRes int id, int size) {
		View v = findViewById(id);
		ViewGroup.LayoutParams lp = v.getLayoutParams();
		lp.width = lp.height = size;
		v.setLayoutParams(lp);
	}

	private void setHeight(@IdRes int id, int h) {
		View v = findViewById(id);
		ViewGroup.LayoutParams lp = v.getLayoutParams();
		lp.height = h;
		v.setLayoutParams(lp);
	}

	private void setHeight(View v, int h) {
		ViewGroup.LayoutParams lp = v.getLayoutParams();
		lp.height = h;
		v.setLayoutParams(lp);
	}

	public boolean isActive() {
		return (mask & ~(MASK_SUPPRESSED | MASK_COVERED)) != 0;
	}

	public boolean isSuppressed() {
		return (mask & MASK_SUPPRESSED) != 0;
	}

	/**
	 * Hides the panel while a screen with its own full player UI (the Music tab) is showing, and
	 * restores whatever it would otherwise be once that screen goes away.
	 */
	public void setSuppressed(boolean suppressed) {
		if (suppressed == isSuppressed()) {
			// Already suppressed: re-assert it, in case a path that shows the panel directly (e.g. the
			// Android Auto focus recovery) brought it back meanwhile.
			if (suppressed && ((mask & MASK_VIDEO_MODE) == 0) && (getVisibility() != GONE)) {
				super.setVisibility(GONE);
				notifyControlPanelVisibility();
			}
			if (suppressed) getActivity().refreshContentInsets();
			return;
		}

		// Deliberately not animated: this is the Music tab coming or going, which has its own
		// entrance/exit animation; the panel fading on top of that only fought with it.
		animate().cancel();
		setAlpha(1f);

		if (suppressed) {
			mask |= MASK_SUPPRESSED;
			if ((mask & MASK_VIDEO_MODE) == 0) super.setVisibility(GONE);
		} else {
			mask &= ~MASK_SUPPRESSED;
			if ((mask & MASK_VIDEO_MODE) == 0)
				super.setVisibility(((mask & (MASK_VISIBLE | MASK_COVERED)) == MASK_VISIBLE) ?
						VISIBLE : GONE);
		}

		notifyControlPanelVisibility();
		MainActivityDelegate a = getActivity();
		checkPlaybackTimer(a);
		// The content's bottom padding reserves room for this panel; re-apply it right away rather
		// than on the next layout pass, which drew one frame (or more) with the stale padding. And
		// once more after that pass, once everything has settled into its final place.
		a.refreshContentInsets();
		post(a::refreshContentInsets);
	}

	public boolean isCovered() {
		return (mask & MASK_COVERED) != 0;
	}

	/**
	 * Fades the panel away while something laid over the screen needs its room (the YouTube tab's
	 * search/Up next panel), and fades it back in -- if it's otherwise meant to show -- once that
	 * goes. Video mode (fullscreen) and the Music tab's suppression still decide on their own.
	 */
	public void setCovered(boolean covered) {
		if (covered == isCovered()) return;
		MainActivityDelegate a = getActivity();

		if (covered) {
			mask |= MASK_COVERED;
			if ((mask & (MASK_VIDEO_MODE | MASK_SUPPRESSED)) == 0) hideAnimated(a);
		} else {
			mask &= ~MASK_COVERED;
			if (((mask & (MASK_VIDEO_MODE | MASK_SUPPRESSED)) == 0) && ((mask & MASK_VISIBLE) != 0)) {
				if (getVisibility() != VISIBLE) {
					a.glideFabsAfterLayout();
					fadeIn(this, true);
					post(a::refreshContentInsets);
				} else {
					// Still fading out: back from wherever it got to.
					animate().cancel();
					animate().alpha(1f).setDuration(FADE_DURATION).start();
				}
			}
		}

		notifyControlPanelVisibility();
		checkPlaybackTimer(a);
	}

	/**
	 * Notifies the currently active {@link VideoView}'s Info Overlay of this panel's real on-screen
	 * visibility, for the "only show while control panel is visible" overlay option -- called from
	 * every place in this class that flips visibility, including the several spots below that call
	 * {@code super.setVisibility(...)} directly rather than going through the override just below,
	 * since that bypasses it entirely.
	 */
	private void notifyControlPanelVisibility() {
		VideoView vv = getActivity().getActiveVideoView();
		if (vv != null) vv.setControlPanelVisible(isVisible(this));
	}

	@Override
	public void setVisibility(int visibility) {
		MainActivityDelegate a = getActivity();

		if (visibility == VISIBLE) {
			mask |= MASK_VISIBLE;
			if ((mask & (MASK_VIDEO_MODE | MASK_SUPPRESSED | MASK_COVERED)) != 0) return;

			if (getVisibility() != VISIBLE) {
				// Fades in, while the floating buttons sitting on it glide up out of its way.
				a.glideFabsAfterLayout();
				fadeIn(this, true);
			} else {
				animate().cancel();
				setAlpha(1f);
			}

			if (a.getPrefs().getHideBarsPref(a)) {
				a.setBarsHidden(true);
				setShowHideBarsIcon(a);
			}
		} else {
			mask &= ~MASK_VISIBLE;
			hideAnimated(a);
			a.getFloatingButton().setVisibility(VISIBLE);

			if (a.isBarsHidden()) {
				a.setBarsHidden(false);
				setShowHideBarsIcon(a);
			}
		}

		notifyControlPanelVisibility();
		checkPlaybackTimer(a);
	}

	public void enableVideoMode() {
		MainActivityDelegate a = getActivity();
		hideTimer = null;
		mask |= MASK_VIDEO_MODE;
		setVideoLook(true);
		a.setBarsHidden(true);
		setShowHideBarsIcon(a);
		// The show_hide_bars_icon toggle (whose only purpose is revealing the system nav bar) is
		// kept for local playback -- still the only in-panel way to do that there -- but dropped for
		// a web-embedded source (YouTube), which already has its own fullscreen chrome. Only the icon
		// itself is hidden, not the whole show_hide_bars row: that row also holds seek_time (the
		// elapsed-time label), which should stay visible and clickable regardless. Disabling the
		// row's own click handler (rather than leaving a dead icon-less tap target that still
		// silently reveals the nav bar) keeps that behavior fully gone, not just invisible.
		boolean nativeFullscreen = isNativeFullscreen(a);
		findViewById(R.id.show_hide_bars_icon).setVisibility(nativeFullscreen ? GONE : VISIBLE);
		findViewById(R.id.show_hide_bars).setClickable(!nativeFullscreen);

		View fb = a.getFloatingButton();
		List<View> extra = a.getEnabledExtraFabs();
		int delay = getStartDelay();

		if (delay == 0) {
			hideFab(fb);
			for (View f : extra) hideFab(f);
			super.setVisibility(GONE);
			showVideoControls(a.getActiveVideoView(), false, false);
		} else {
			showFab(fb);
			for (View f : extra) showFab(f);
			super.setVisibility(VISIBLE);
			// The middle buttons come with the panel; the title only ever with a tap on the video.
			showVideoControls(a.getActiveVideoView(), true, false);
			hideTimer = new HideTimer(a, delay, false, fabs(fb, extra));
			a.postDelayed(hideTimer, delay);
		}

		notifyControlPanelVisibility();
		checkPlaybackTimer(a);
	}

	/**
	 * True while a web-embedded video (YouTube) is in its own native fullscreen -- see
	 * {@link #enableVideoMode()}'s use of this for {@code show_hide_bars_icon}.
	 */
	private boolean isNativeFullscreen(MainActivityDelegate a) {
		VideoView vv = a.getActiveVideoView();
		return (vv != null) && vv.hasNativeFullscreen();
	}

	/** The primary FAB followed by the enabled extra ones -- what shows and hides together. */
	private static View[] fabs(View fb, List<View> extra) {
		View[] all = new View[extra.size() + 1];
		all[0] = fb;
		for (int i = 0; i < extra.size(); i++) all[i + 1] = extra.get(i);
		return all;
	}

	public void disableVideoMode() {
		MainActivityDelegate a = getActivity();
		hideTimer = null;
		mask &= ~MASK_VIDEO_MODE;
		seekStreakUntil = 0;
		applyPendingSeek();
		hideVideoControls(false);
		setVideoLook(false);
		showFab(a.getFloatingButton());
		// Whatever their visibility, none is left half way through a fade of ours.
		for (View f : a.getEnabledExtraFabs()) {
			if (f.getVisibility() == VISIBLE) showFab(f);
		}
		findViewById(R.id.show_hide_bars).setVisibility(VISIBLE);
		findViewById(R.id.show_hide_bars).setClickable(true);
		findViewById(R.id.show_hide_bars_icon).setVisibility(VISIBLE);

		if (((mask & MASK_VISIBLE) == 0) || ((mask & (MASK_SUPPRESSED | MASK_COVERED)) != 0)) {
			super.setVisibility(GONE);
			a.setBarsHidden(false);
		} else {
			super.setVisibility(VISIBLE);
			a.setBarsHidden(a.getPrefs().getHideBarsPref(a));
		}

		setShowHideBarsIcon(a);
		notifyControlPanelVisibility();
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		MainActivityDelegate a = getActivity();
		restartVideoHideTimer();
		return a.interceptTouchEvent(e, me -> {
			gestureSource = this;
			gestureDetector.onTouchEvent(me);
			return super.onTouchEvent(me);
		});
	}

	@Override
	public boolean onSwipeLeft(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextButtonClick(true);
		return true;
	}

	@Override
	public boolean onSwipeRight(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextButtonClick(false);
		return true;
	}

	@Override
	public boolean onSwipeUp(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextFolderClick(false);
		return true;
	}

	@Override
	public boolean onSwipeDown(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextFolderClick(true);
		return true;
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		boolean horizontal = Math.abs(distanceX) >= Math.abs(distanceY);
		long time = System.currentTimeMillis();
		long diff;

		if (horizontal) {
			diff = time - scrollStamp;
			if (diff < 100) return true;
			scrollStamp = time;
		} else {
			diff = time + scrollStamp;
			if (diff < 100) return true;
			scrollStamp = -time;
		}

		if (diff > 500) return true;

		if (horizontal) {
			FermataServiceUiBinder b = getActivity().getMediaServiceBinder();

			switch (e2.getPointerCount()) {
				case 1 -> b.onRwFfButtonClick(distanceX < 0);
				case 2 -> b.onRwFfButtonLongClick(distanceX < 0);
				default -> b.onPrevNextButtonLongClick(distanceX < 0);
			}

			onVideoSeek();
		} else if (e2.getPointerCount() == 2) {
			if (!getActivity().getPrefs().getChangeBrightnessPref()) return true;
			MainActivityDelegate a = getActivity();
			int br = a.getBrightness();
			br = (distanceY > 0) ? Math.min(255, br + 10) : Math.max(0, br - 10);
			a.setBrightness(br);
		} else {
			MediaEngine eng = getActivity().getMediaServiceBinder().getCurrentEngine();
			return (eng != null) && eng.adjustVolume((distanceY > 0) ? ADJUST_RAISE : ADJUST_LOWER);
		}

		return true;
	}

	/**
	 * Like YouTube: a double tap on the right half of the video jumps {@link #DOUBLE_TAP_SEEK_SEC}
	 * seconds forward, on the left half as much back, and every further tap that follows quickly
	 * (see {@link #onVideoViewTouch}) jumps as much again.
	 */
	@Override
	public boolean onDoubleTap(MotionEvent e) {
		if (!(gestureSource instanceof VideoView vv)) return false;
		seekStreakSeconds = 0;
		doubleTapSeek(vv, e);
		return true;
	}

	private void doubleTapSeek(VideoView vv, MotionEvent e) {
		MainActivityDelegate a = getActivity();
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		MediaEngine eng = b.getCurrentEngine();
		if ((eng == null) || !eng.canSeek()) {
			seekStreakUntil = 0;
			return;
		}

		// The touch may come through the controls overlay, a child of the video view lying over all of
		// it: either way these are the video view's own coordinates.
		boolean ff = e.getX() >= (vv.getWidth() / 2f);
		if (ff != seekStreakForward) {
			// The other side: what the first side asked for goes now, the new side counts from 0.
			seekStreakSeconds = 0;
			applyPendingSeek();
		}
		seekStreakForward = ff;
		seekStreakSeconds += DOUBLE_TAP_SEEK_SEC;
		seekStreakUntil = SystemClock.uptimeMillis() + SEEK_STREAK_MS;
		pendingSeekSec += ff ? DOUBLE_TAP_SEEK_SEC : -DOUBLE_TAP_SEEK_SEC;
		DiagnosticLog.log("TRANSPORT", "double tap seek", "ff=" + ff, "streak=" + seekStreakSeconds,
				"state=" + b.getMediaSessionCallback().getPlaybackState().getState());
		removeCallbacks(applySeekTask);
		postDelayed(applySeekTask, SEEK_APPLY_DELAY_MS);
		// Nothing but the seek feedback, as YouTube does: the controls (and an Info Overlay shown only
		// with them) go if they were up, and don't come up for it.
		if (((mask & MASK_VIDEO_MODE) != 0) && (getVisibility() == VISIBLE)) hideVideoUi(a);
		vv.getControls().showSeek(ff, seekStreakSeconds, e.getX(), e.getY());
	}

	/** Makes the seek the streak's taps asked for so far, if any. */
	private void applyPendingSeek() {
		removeCallbacks(applySeekTask);
		int sec = pendingSeekSec;
		pendingSeekSec = 0;
		if (sec == 0) return;
		MediaSessionCallback cb = getActivity().getMediaSessionCallback();
		DiagnosticLog.log("TRANSPORT", "double tap seek applied", "sec=" + sec,
				"state=" + cb.getPlaybackState().getState());
		cb.rewindFastForward(sec > 0, Math.abs(sec), PlaybackControlPrefs.TIME_UNIT_SECOND, 1);
	}

	@Override
	public boolean onSingleTapConfirmed(MotionEvent e) {
		if (!(gestureSource instanceof VideoView)) return false;
		return onTouch((VideoView) gestureSource);
	}

	public boolean onTouch(VideoView video) {
		MainActivityDelegate a = getActivity();
		BodyLayout b = a.getBody();

		int delay = getTouchDelay();
		if (delay == 0) return false;

		View fb = a.getFloatingButton();
		List<View> extra = a.getEnabledExtraFabs();

		// The panel and the middle buttons show and hide together.
		boolean shown = getVisibility() == VISIBLE;

		if (shown) {
			hideVideoUi(a);
		} else {
			if (getVisibility() != VISIBLE) fadeIn(this, true);
			if (fb.getVisibility() != VISIBLE) fadeIn(fb, false);
			for (View f : extra) if (f.getVisibility() != VISIBLE) fadeIn(f, false);
			if (a.getPrefs().getSysBarsOnVideoTouchPref()) a.setFullScreen(false);
			clearFocus();
			video.getControls().hideSeek();
			showVideoControls(video, true, true);
			hideTimer = new HideTimer(a, delay, false, fabs(fb, extra));
			a.postDelayed(hideTimer, delay);
		}

		checkPlaybackTimer(a);
		return true;
	}

	/**
	 * Fades the fullscreen video controls away: the panel, the floating buttons, the middle
	 * buttons and title (and with the panel, an Info Overlay shown only with it).
	 */
	private void hideVideoUi(MainActivityDelegate a) {
		hideTimer = null;
		fadeOut(this, true);
		fadeOut(a.getFloatingButton(), false);
		for (View f : a.getEnabledExtraFabs()) fadeOut(f, false);
		if (a.getPrefs().getSysBarsOnVideoTouchPref()) a.setFullScreen(true);
		hideVideoControls(true);
	}

	private static final long FADE_DURATION = 200L;

	private void fadeOut(View v, boolean self) {
		v.animate().cancel();
		v.animate().alpha(0f).setDuration(FADE_DURATION).withEndAction(() -> {
			if (self) {
				super.setVisibility(GONE);
				notifyControlPanelVisibility();
			} else {
				// Back to opaque once hidden: whatever shows it next with a plain setVisibility()
				// must not get an invisible button (an empty slot in the floating button pill).
				v.setVisibility(GONE);
				v.setAlpha(1f);
			}
		}).start();
	}

	/**
	 * Shows a floating button right away, ending any fade of ours on it first: one cut short (or
	 * finished) left it see-through or fully transparent, drawn faintly or not at all while the
	 * pill behind the buttons still showed.
	 */
	private static void showFab(View f) {
		settleFab(f);
		f.setVisibility(VISIBLE);
	}

	private static void hideFab(View f) {
		settleFab(f);
		f.setVisibility(GONE);
	}

	private static void settleFab(View f) {
		f.animate().cancel();
		f.setAlpha(1f);
		// Cancelling also ends a press bounce: back to the button's own size.
		if (f instanceof FloatingButton b) {
			b.setScaleX(b.getScale());
			b.setScaleY(b.getScale());
		}
	}

	/**
	 * Fades the panel out and only then makes it GONE (the floating buttons gliding down as it
	 * goes) -- unless something showed it again meanwhile, or video mode took it over.
	 */
	private void hideAnimated(MainActivityDelegate a) {
		animate().cancel();
		if ((getVisibility() != VISIBLE) || !isLaidOut() || !isAttachedToWindow()) {
			super.setVisibility(GONE);
			setAlpha(1f);
			return;
		}
		animate().alpha(0f).setDuration(FADE_DURATION).withEndAction(() -> {
			// Not shown again meanwhile (or still covered), and video mode didn't take it over.
			if (((mask & MASK_VIDEO_MODE) == 0) &&
					(((mask & MASK_VISIBLE) == 0) || ((mask & MASK_COVERED) != 0))) {
				a.glideFabsAfterLayout();
				super.setVisibility(GONE);
				notifyControlPanelVisibility();
				a.refreshContentInsets();
			}
			setAlpha(1f);
		}).start();
	}

	private void fadeIn(View v, boolean self) {
		v.animate().cancel();
		v.setAlpha(0f);
		if (self) {
			super.setVisibility(VISIBLE);
			notifyControlPanelVisibility();
		} else v.setVisibility(VISIBLE);
		v.animate().alpha(1f).setDuration(FADE_DURATION).start();
	}

	public void onVideoViewTouch(VideoView view, MotionEvent e) {
		gestureSource = view;

		// Within a double tap seek streak, each further tap seeks again right away and is not a
		// gesture of its own (it would otherwise be taken for a single tap, or start a new double tap).
		if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
			seekStreakTouch = (e.getPointerCount() == 1) &&
					(SystemClock.uptimeMillis() < seekStreakUntil);
			if (seekStreakTouch) {
				doubleTapSeek(view, e);
				return;
			}
		} else if (seekStreakTouch) {
			int act = e.getActionMasked();
			if ((act == MotionEvent.ACTION_UP) || (act == MotionEvent.ACTION_CANCEL)) {
				seekStreakTouch = false;
			}
			return;
		}

		gestureDetector.onTouchEvent(e);
	}

	/**
	 * Whether a double tap seek streak is going on: a tap now, even one landing on a middle button,
	 * is one more seek (see VideoControlsOverlay).
	 */
	public boolean isSeekStreakActive() {
		return SystemClock.uptimeMillis() < seekStreakUntil;
	}

	/** Restarts the countdown that hides the fullscreen video controls, if it's running. */
	public void restartVideoHideTimer() {
		if (hideTimer == null) return;
		MainActivityDelegate a = getActivity();
		int delay = getTouchDelay();
		hideTimer = new HideTimer(a, delay, false, hideTimer.views);
		a.postDelayed(hideTimer, delay);
	}

	/**
	 * Shows the middle buttons (and, for a tap on the video, the title) over {@code vv}, synced to
	 * the playback state and the item playing, and takes them off whichever video view had them
	 * before. Fullscreen only.
	 */
	private void showVideoControls(@Nullable VideoView vv, boolean center, boolean title) {
		if ((controlsHost != null) && (controlsHost != vv)) controlsHost.showControls(false, false, false);
		controlsHost = vv;
		if (vv == null) return;
		if ((mask & MASK_VIDEO_MODE) == 0) {
			center = title = false;
		}
		if (center || title) syncVideoControls();
		vv.showControls(center, title, true);
	}

	private void hideVideoControls(boolean animate) {
		VideoView vv = controlsHost;
		if (vv == null) return;
		vv.showControls(false, false, animate);
		if (!animate) {
			vv.getControls().hideSeek();
			controlsHost = null;
		}
	}

	/**
	 * Copies the panel's play/pause state and the playing item's title onto the fullscreen video
	 * controls -- called by {@code FermataServiceUiBinder} whenever either changes.
	 */
	public void syncVideoControls() {
		VideoView vv = controlsHost;
		if (vv == null) return;
		VideoControlsOverlay c = vv.getControls();
		View pp = findViewById(R.id.control_play_pause);
		c.setPlayPauseState(pp.isSelected(), pp.isActivated());

		FermataServiceUiBinder b = getActivity().getMediaServiceBinder();
		CharSequence[] t = titleOf(b.getMetadata(), b.getCurrentItem());
		c.setTitle(t[0], t[1]);
	}

	/** The title and artist to show for the playing item: {title, artist}, either can be null. */
	private static CharSequence[] titleOf(@Nullable MediaMetadataCompat md, @Nullable PlayableItem i) {
		CharSequence t = null;
		CharSequence sub = null;
		if (md != null) {
			// Not the DISPLAY_ ones: those can carry the current subtitle line (see
			// MediaSessionCallback#accept).
			t = md.getText(MediaMetadataCompat.METADATA_KEY_TITLE);
			sub = md.getText(MediaMetadataCompat.METADATA_KEY_ARTIST);
			if ((sub == null) || (sub.length() == 0))
				sub = md.getText(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST);
		}
		if (((t == null) || (t.length() == 0)) && (i != null)) t = i.getName();
		return new CharSequence[]{t, sub};
	}

	/**
	 * Shows the playing item's art and title on the compact layouts: called by
	 * {@code FermataServiceUiBinder} whenever the item, its metadata or the play state changes.
	 */
	public void syncNowPlaying() {
		if (!showsNowPlaying) return;
		FermataServiceUiBinder b = getActivity().getMediaServiceBinder();
		if (b == null) return;
		PlayableItem i = b.getMediaSessionCallback().getCurrentItem();
		MediaMetadataCompat md = b.getMetadata();
		CharSequence[] t = titleOf(md, i);
		setText(title, t[0]);
		setText(subtitle, t[1]);
		loadArt(i, md);
	}

	private static void setText(TextView v, @Nullable CharSequence text) {
		boolean empty = (text == null) || (text.length() == 0);
		if (!empty && !text.toString().contentEquals(v.getText())) v.setText(text);
		v.setVisibility(empty ? GONE : VISIBLE);
	}

	/** As the Music tab does (see MusicPlayerFragment#loadArt), the first art found wins. */
	private void loadArt(@Nullable PlayableItem i, @Nullable MediaMetadataCompat md) {
		if (i == null) {
			showArt(null, null, null);
			return;
		}

		String uri = (i instanceof MusicTrackItem t) ? t.getArtUri() : null;
		if ((uri == null) && (md != null)) {
			Bitmap bm = md.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
			if (bm == null) bm = md.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
			if (bm != null) {
				if ((artItem != i) || (artKey != bm)) showArt(i, bm, bm);
				return;
			}
			uri = md.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI);
			if (uri == null) uri = md.getString(MediaMetadataCompat.METADATA_KEY_ART_URI);
			if (uri == null) uri = md.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI);
		}

		if (uri != null) {
			loadArt(i, uri);
			return;
		}
		// Nothing in the metadata: the item's own icon uri, looked up once per item (this runs on
		// every play/pause too).
		if ((artItem == i) && ((artKey != null) || (artLoading != null))) return;
		if (artItem != i) showArt(i, null, null);
		artLoading = "";
		i.getIconUri().main().onSuccess(u -> {
			if (artItem != i) return;
			if (u != null) loadArt(i, u.toString());
		});
	}

	private void loadArt(PlayableItem i, String uri) {
		if ((artItem == i) && (uri.equals(artKey) || uri.equals(artLoading))) return;
		// Keeps the item's current picture (if any) while the new one loads.
		if (artItem != i) showArt(i, null, null);
		artLoading = uri;
		getActivity().getLib().getBitmap(uri).main().onCompletion((bm, err) -> {
			// On failure artLoading stays: the same uri isn't tried again on every play/pause.
			if ((artItem != i) || (bm == null) || !uri.equals(artLoading)) return;
			artLoading = null;
			showArt(i, MusicPlayerFragment.cropLetterbox(bm), uri);
		});
	}

	/** Shows {@code bm}, or the item's own icon (video, audio track) for null. */
	private void showArt(@Nullable PlayableItem i, @Nullable Bitmap bm, @Nullable Object key) {
		if (artItem != i) artLoading = null;
		artItem = i;
		artKey = (bm == null) ? null : key;

		if (bm == null) {
			art.setImageResource((i == null) ? R.drawable.music : i.getIcon());
			art.setImageTintList(ColorStateList.valueOf(labelColor()));
			art.setScaleType(ImageView.ScaleType.FIT_CENTER);
		} else {
			art.setImageDrawable(new BitmapDrawable(getResources(), bm));
			art.setImageTintList(null);
			art.setScaleType(ImageView.ScaleType.CENTER_CROP);
		}
		updateArtPadding();
	}

	/** The placeholder icon sits in the middle of the art's box, a picture fills it. */
	private void updateArtPadding() {
		int pad = (artKey == null) ? art.getLayoutParams().width / 5 : 0;
		art.setPadding(pad, pad, pad, pad);
	}

	public void onVideoSeek() {
		MainActivityDelegate a = getActivity();
		VideoView vv = a.getMediaServiceBinder().getMediaSessionCallback().getVideoView();

		if (vv == null) {
			if (gestureSource instanceof VideoView) vv = (VideoView) gestureSource;
			else return;
		}

		View fb = a.getFloatingButton();
		List<View> extra = a.getEnabledExtraFabs();
		int delay = getSeekDelay();
		// The middle buttons always come and go with the panel (its seek bar line); the title only
		// ever with a single tap, so a seek takes it away.
		showVideoControls(vv, true, false);
		super.setVisibility(VISIBLE);
		showFab(fb);
		for (View f : extra) showFab(f);
		clearFocus();
		hideTimer = new HideTimer(a, delay, true, fabs(fb, extra));
		a.postDelayed(hideTimer, delay);
		notifyControlPanelVisibility();
		checkPlaybackTimer(a);
	}

	public boolean isVideoSeekMode() {
		HideTimer t = hideTimer;
		return (t != null) && t.seekMode;
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (handleActivityDestroyEvent(a, e)) {
			a.getMediaServiceBinder().unbind();
			a.getPrefs().removeBroadcastListener(this);
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		MainActivityDelegate a = getActivity();

		if (MainActivityPrefs.hasControlPanelSizePref(a, prefs)) {
			setSize(a.getPrefs().getControlPanelSizePref(a));
		} else if ((mask == MASK_VISIBLE) && MainActivityPrefs.hasHideBarsPref(a, prefs)) {
			if (a.getPrefs().getHideBarsPref(a)) a.setBarsHidden(getVisibility() == VISIBLE);
			else if (a.isBarsHidden()) a.setBarsHidden(false);
			setShowHideBarsIcon(a);
		}
	}

	public View focusSearch() {
		View v = findViewById(R.id.seek_bar);
		if (isVisible(v)) return v;
		v = findViewById(R.id.control_play_pause);
		return isVisible(v) ? v : findViewById(R.id.control_menu_button);
	}

	@Override
	public View focusSearch(View focused, int direction) {
		if (focused == null) return super.focusSearch(null, direction);

		if (direction == FOCUS_UP) {
			if (isTopLine(focused)) {
				MainActivityDelegate a = getActivity();
				if (a.isVideoMode()) {
					// Up to the play/pause in the middle of the picture, when it's there.
					VideoView vv = controlsHost;
					if ((vv != null) && vv.getControls().isCenterShown())
						return vv.getControls().getPlayPauseButton();
					return a.getBody().getVideoView();
				}
				View v = MediaItemListView.focusSearchLast(getContext(), focused);
				if (v != null) return v;
			} else if (layoutMode == LAYOUT_CLASSIC) {
				if (!isVisible(findViewById(R.id.seek_bar))) return findViewById(R.id.control_menu_button);
			}
		} else if (direction == FOCUS_DOWN) {
			if (isBottomLine(focused)) {
				NavBarView n = getActivity().getNavBar();
				if (isVisible(n) && n.isBottom()) return n.focusSearch();
			}
		}

		return super.focusSearch(focused, direction);
	}

	/** The classic layout has the seek bar's line on top, the compact two-line one under the buttons. */
	private boolean isTopLine(View v) {
		if (layoutMode == LAYOUT_ROW) return true;
		return (layoutMode == LAYOUT_STACKED) != isLine1(v);
	}

	private boolean isBottomLine(View v) {
		if (layoutMode == LAYOUT_ROW) return true;
		return (layoutMode == LAYOUT_STACKED) == isLine1(v);
	}

	private boolean isLine1(View v) {
		int id = v.getId();
		return id == R.id.seek_bar || id == R.id.show_hide_bars || id == R.id.control_menu_button;
	}

	private void showHideBars(View v) {
		MainActivityDelegate a = getActivity();
		a.setBarsHidden(!a.isBarsHidden());
		setShowHideBarsIcon(a);
	}

	/**
	 * Keeps this corner icon in sync when the app's bars are hidden/shown from elsewhere -- e.g.
	 * {@link MainActivityDelegate#toggleVideoBars()}, driven by FAB2's default fullscreen toggle
	 * during local video playback.
	 */
	public void refreshShowHideBarsIcon() {
		setShowHideBarsIcon(getActivity());
	}

	public void showMenu() {
		if (isActive()) showMenu(this);
	}

	private void showMenu(View v) {
		MainActivityDelegate a = getActivity();
		MediaEngine eng = a.getMediaServiceBinder().getCurrentEngine();
		PlayableItem i = (eng == null) ? null : eng.getSource();
		if (i != null) new MenuHandler(getMenu(a), i, eng).show();
	}

	private OverlayMenu getMenu(MainActivityDelegate a) {
		return a.findViewById(R.id.control_menu);
	}

	private void setShowHideBarsIcon(MainActivityDelegate a) {
		a.post(() -> showHideBars.setImageResource(
				a.isBarsHidden() ? R.drawable.expand : me.aap.utils.R.drawable.collapse));
	}

	private MainActivityDelegate getActivity() {
		return MainActivityDelegate.get(getContext());
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		return true;
	}

	private void checkPlaybackTimer(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		int t = cb.getPlaybackTimer();

		if (t <= 0) {
			if (playbackTimer != null) {
				((ViewGroup) getParent()).removeView(playbackTimer);
				playbackTimer = null;
			}
		} else {
			if (playbackTimer == null) {
				Context ctx = getContext();
				playbackTimer = new MaterialTextView(ctx);
				// Give it an id: it is added straight into the root ConstraintLayout, and anything
				// that walks that layout via ConstraintSet requires every direct child to have one.
				playbackTimer.setId(View.generateViewId());
				((ViewGroup) getParent()).addView(playbackTimer);
				playbackTimer.setBackgroundResource(R.drawable.playback_timer_bg);
				playbackTimer.setTextAppearance(textAppearance);
				ViewGroup.LayoutParams lp = playbackTimer.getLayoutParams();

				if (lp instanceof LayoutParams clp) {
					clp.startToStart = PARENT_ID;
					clp.endToEnd = PARENT_ID;
					clp.bottomToTop = getId();
					clp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				}

				playbackTimer.setOnClickListener(
						v -> getMenu(a).show(b -> new TimerMenuHandler(a).build(b)));
			}

			if (getVisibility() != VISIBLE) {
				playbackTimer.setVisibility(GONE);
				return;
			}

			try (SharedTextBuilder tb = SharedTextBuilder.get()) {
				TextUtils.timeToString(tb, t);
				playbackTimer.setText(tb);
			}

			playbackTimer.setVisibility(VISIBLE);
			a.postDelayed(() -> checkPlaybackTimer(a), 1000);
		}
	}

	private final class MenuHandler extends MediaItemMenuHandler {
		private final MediaEngine engine;

		public MenuHandler(OverlayMenu menu, Item item, MediaEngine engine) {
			super(menu, item);
			this.engine = engine;
		}

		@Override
		protected boolean addVideoMenu() {
			return !engine.hasVideoMenu();
		}

		@Override
		protected boolean addAudioMenu() {
			PlayableItem pi = engine.getSource();
			return (pi != null) && pi.isVideo() && ((engine.getAudioStreamInfo().size() > 1) ||
					getActivity().getMediaSessionCallback().getEngineManager().isVlcPlayerSupported());
		}

		@Override
		protected void buildAudioMenu(OverlayMenu.Builder b) {
			if (engine.getAudioStreamInfo().size() > 1) {
				b.addItem(R.id.select_audio_stream, R.string.select_audio_stream)
						.setSubmenu(this::buildAudioStreamMenu);
			}
			super.buildAudioMenu(b);
		}

		private void buildAudioStreamMenu(OverlayMenu.Builder b) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng == null) return;
			AudioStreamInfo ai = eng.getCurrentAudioStreamInfo();
			List<AudioStreamInfo> streams = eng.getAudioStreamInfo();
			b.setSelectionHandler(this::audioStreamSelected);

			for (int i = 0; i < streams.size(); i++) {
				AudioStreamInfo s = streams.get(i);
				b.addItem(UiUtils.getArrayItemId(i), s.toString()).setData(s).setChecked(s.equals(ai));
			}
		}

		private boolean audioStreamSelected(OverlayMenuItem i) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng != null) {
				AudioStreamInfo ai = i.getData();
				PlayableItem pi = (PlayableItem) getItem();

				if (ai.equals(eng.getCurrentAudioStreamInfo())) {
					pi.getPrefs().setAudioIdPref(null);
					eng.setCurrentAudioStream(null);
				} else {
					eng.setCurrentAudioStream(ai);
					pi.getPrefs().setAudioIdPref(ai.getId());
				}
			}
			return true;
		}

		@Override
		protected boolean addSubtitlesMenu() {
			return engine.isSubtitlesSupported();
		}

		@Override
		protected void buildSubtitlesMenu(OverlayMenu.Builder b) {
			b.addItem(R.id.select_subtitles, R.string.select_subtitles)
					.setFutureSubmenu(this::buildSubtitleStreamMenu);
			super.buildSubtitlesMenu(b);
		}

		private FutureSupplier<Void> buildSubtitleStreamMenu(OverlayMenu.Builder b) {
			b.setSelectionHandler(this::subtitleStreamSelected);
			return engine.getSubtitleStreamInfo().main().map(streams -> {
				SubtitleStreamInfo si = engine.getCurrentSubtitleStreamInfo();
				for (int i = 0; i < streams.size(); i++) {
					SubtitleStreamInfo s = streams.get(i);
					b.addItem(UiUtils.getArrayItemId(i), s.toString()).setData(s).setChecked(s.equals(si));
				}
				return null;
			});
		}

		private boolean subtitleStreamSelected(OverlayMenuItem i) {
			if (getActivity().getMediaSessionCallback().getEngine() != engine) return true;

			SubtitleStreamInfo si = i.getData();
			PlayableItem pi = (PlayableItem) getItem();

			if (si.equals(engine.getCurrentSubtitleStreamInfo())) {
				pi.getPrefs().setSubIdPref(null);
				engine.setCurrentSubtitleStream(null);
			} else {
				engine.setCurrentSubtitleStream(si);
				pi.getPrefs().setSubIdPref(si.getId());
			}

			return true;
		}

		@Override
		protected void buildPlayableMenu(MainActivityDelegate a, OverlayMenu.Builder b,
																		 PlayableItem pi,
																		 boolean initRepeat) {
			super.buildPlayableMenu(a, b, pi, false);

			BrowsableItemPrefs p = pi.getParent().getPrefs();
			MediaEngine eng = a.getMediaSessionCallback().getEngine();
			if (eng == null) return;

			boolean stream = (pi.isStream());

			if (!pi.isVideo()) {
				// Plain audio playback keeps the original flat menu -- the category grouping below is
				// specifically for the fullscreen video control panel (Audio/Video/Playback all assume
				// a video is playing), and Settings/Exit are video-only entries to begin with.
				eng.contributeToMenu(b);
				buildPlaybackItems(a, b, eng, pi, p, stream);
				if ((eng.supportsAudioEffects() || eng.supportsSoundStage())) {
					b.addItem(R.id.audio_effects_fragment, R.drawable.equalizer, R.string.audio_effects);
				}
				if (MusicPlayer.isEnabled() && !(pi instanceof MusicTrackItem)) {
					b.addItem(R.id.music_play, R.drawable.music, R.string.play_as_music);
				}
				eng.contributeToMenuEnd(b);
				return;
			}

			// Category order: Playback first (repeat/shuffle/speed/timer -- the entries reached for
			// most often while a video is playing), then Video, then Audio.
			b.addItem(R.id.category_playback, R.drawable.playback_settings, R.string.playback)
					.setSubmenu(s -> buildPlaybackItems(a, s, eng, pi, p, stream));

			// For local playback, super.buildPlayableMenu() above already added a "Video" category
			// (addVideoMenu() returns true when engine.hasVideoMenu() is false), whose submenu is our
			// own overridden buildVideoMenu() below -- so Dim screen and any engine-contributed
			// Quality/Scale end up nested in it alongside the existing scaling/hw-accel entries rather
			// than duplicating a second "Video" item. YouTube's engine.hasVideoMenu() is true, so
			// addVideoMenu() skipped adding it there; add it here instead, reusing the same submenu
			// builder so Dim screen still gets nested with YouTube's own Quality/Scale.
			if (eng.hasVideoMenu()) {
				b.addItem(R.id.video, R.drawable.video, R.string.video).setSubmenu(this::buildVideoMenu);
			}

			b.addItem(R.id.category_audio, R.drawable.audiotrack, R.string.audio)
					.setSubmenu(s -> buildAudioCategory(a, s, eng));

			// Navigate away entirely, so keep these last rather than grouped with the categories
			// above. Dim screen settings itself is deliberately not offered here -- this control-panel
			// "..." menu is meant to stay focused on this item's own playback/quality controls, and Dim
			// screen settings (still reachable via the FAB long-press menu, see
			// SecondaryFabMediator/TertiaryFabMediator) is unrelated to any of them.
			b.addItem(R.id.settings_fragment, R.drawable.settings, R.string.settings);
			b.addItem(R.id.nav_exit, R.drawable.exit,
					a.isCarActivityNotMirror() ? R.string.restart : R.string.exit);
		}

		private void buildAudioCategory(MainActivityDelegate a, OverlayMenu.Builder b, MediaEngine eng) {
			b.setSelectionHandler(this);
			b.addItem(R.id.mute_toggle, R.drawable.volume_mute, R.string.action_vol_mute_unmute)
					.setChecked(Action.isMuted(a.getContext()));
			// Keeps the sound going and just drops the picture (and, for YouTube, the video stream).
			if (MusicPlayer.isEnabled()) {
				b.addItem(R.id.music_play, R.drawable.music, R.string.play_as_music);
			}
			if ((eng.supportsAudioEffects() || eng.supportsSoundStage())) {
				b.addItem(R.id.audio_effects_fragment, R.drawable.equalizer, R.string.effects);
			}
			// Engine-contributed items that also navigate away (e.g. YouTube's own Effects/Equalizer
			// entry), added last so they sort below the in-place toggles above.
			eng.contributeToMenuEnd(b);
		}

		@Override
		protected void buildVideoMenu(OverlayMenu.Builder b) {
			b.setSelectionHandler(this);
			b.addItem(R.id.dim_toggle, R.drawable.dim_screen, R.string.dim_screen)
					.setChecked(getActivity().getPrefs().getBooleanPref(MainActivityPrefs.DIM_ENABLED));
			// Engine-contributed video-related items (e.g. YouTube's own Quality/Scale entries).
			engine.contributeToMenu(b);
			// Local engines (engine.hasVideoMenu() == false): append the base class's own video-scaling/
			// hw-accel/watched-threshold/audio-track entries into this same category. YouTube
			// (hasVideoMenu() == true) has none of those concepts, so skip it there.
			if (!engine.hasVideoMenu()) super.buildVideoMenu(b);
		}

		private void buildPlaybackItems(MainActivityDelegate a, OverlayMenu.Builder b, MediaEngine eng,
																		 PlayableItem pi, BrowsableItemPrefs p, boolean stream) {
			b.setSelectionHandler(this);

			if (!stream && !pi.isExternal()) {
				if (pi.isRepeatItemEnabled() || p.getRepeatPref()) {
					b.addItem(R.id.repeat, R.drawable.repeat_filled, R.string.repeat).setSubmenu(s -> {
						buildRepeatMenu(s);
						s.addItem(R.id.repeat_disable_all, R.string.repeat_disable);
					});
				} else {
					b.addItem(R.id.repeat_enable, R.drawable.repeat, R.string.repeat)
							.setSubmenu(this::buildRepeatMenu);
				}

				if (p.getShufflePref()) {
					b.addItem(R.id.shuffle_disable, R.drawable.shuffle_filled, R.string.shuffle_disable);
				} else {
					b.addItem(R.id.shuffle_enable, R.drawable.shuffle, R.string.shuffle);
				}
			}

			// Engine-contributed items that belong in this category (e.g. YouTube's own Repeat/Shuffle,
			// which apply even though pi.isExternal() is true for every YouTube item).
			eng.contributeToPlaybackMenu(b);

			if (!stream) {
				b.addItem(R.id.speed, R.drawable.speed, R.string.speed)
						.setSubmenu(s -> new SpeedMenuHandler().build(s, getItem()));
			}

			b.addItem(R.id.timer, R.drawable.timer, R.string.timer)
					.setSubmenu(s -> new TimerMenuHandler(a).build(s));

			// Engine-contributed items that go below Timer (e.g. YouTube's Search/Up next).
			eng.contributeToPlaybackMenuEnd(b);
		}

		private void buildRepeatMenu(OverlayMenu.Builder b) {
			b.setSelectionHandler(this);
			b.addItem(R.id.repeat_track, R.string.current_track);
			b.addItem(R.id.repeat_folder, R.string.current_folder);
		}

		@Override
		public boolean menuItemSelected(OverlayMenuItem i) {
			int id = i.getItemId();
			PlayableItem pi;
			MediaEngine eng;

			if (id == R.id.music_play) {
				MusicPlayer.playCurrentAsMusic(getActivity());
				return true;
			} else if (id == R.id.audio_effects_fragment) {
				eng = getActivity().getMediaSessionCallback().getEngine();
				if ((eng != null) && (eng.supportsAudioEffects() || eng.supportsSoundStage()))
					getActivity().showFragment(R.id.audio_effects_fragment);
				return true;
			} else if (id == R.id.repeat_track || id == R.id.repeat_folder ||
					id == R.id.repeat_disable_all) {
				pi = (PlayableItem) getItem();
				pi.setRepeatItemEnabled(id == R.id.repeat_track);
				pi.getParent().getPrefs().setRepeatPref(id == R.id.repeat_folder);
				return true;
			} else if (id == R.id.shuffle_enable || id == R.id.shuffle_disable) {
				pi = (PlayableItem) getItem();
				pi.getParent().getPrefs().setShufflePref(id == R.id.shuffle_enable);
				return true;
			} else if (id == R.id.dim_toggle) {
				MainActivityPrefs p = getActivity().getPrefs();
				p.applyBooleanPref(MainActivityPrefs.DIM_ENABLED, !p.getBooleanPref(MainActivityPrefs.DIM_ENABLED));
				return true;
			} else if (id == R.id.mute_toggle) {
				MainActivityDelegate a = getActivity();
				Action.VOLUME_MUTE_UNMUTE.getHandler()
						.handle(a.getMediaSessionCallback(), a, SystemClock.uptimeMillis());
				return true;
			} else if (id == R.id.settings_fragment) {
				MainActivityDelegate a = getActivity();
				a.exitVideoMode();
				a.showFragment(R.id.settings_fragment);
				return true;
			} else if (id == R.id.nav_exit) {
				MainActivityDelegate a = getActivity();
				a.getMediaSessionCallback().onStop();
				a.finish();
				if (a.isCarActivityNotMirror()) a.getHandler().postDelayed(() -> System.exit(0), 500);
				return true;
			}

			return super.menuItemSelected(i);
		}
	}

	private final class SpeedMenuHandler implements OverlayMenu.CloseHandler {
		private PrefStore store;

		void build(OverlayMenu.Builder b, Item item) {
			store = new PrefStore(item);
			PreferenceSet set = new PreferenceSet();

			set.addFloatPref(o -> {
				o.title = R.string.speed;
				o.store = store;
				o.pref = MediaPrefs.SPEED;
				o.scale = 0.1f;
				o.seekMin = 1;
				o.seekMax = 20;
			});
			set.addBooleanPref(o -> {
				o.title = R.string.current_track;
				o.store = store;
				o.pref = store.TRACK;
			});
			set.addBooleanPref(o -> {
				o.title = R.string.current_folder;
				o.store = store;
				o.pref = store.FOLDER;
			});

			set.addToMenu(b, true);
			b.setCloseHandlerHandler(this);
		}

		@Override
		public void menuClosed(OverlayMenu menu) {
			store.apply();
		}

		private class PrefStore extends BasicPreferenceStore {
			final Pref<BooleanSupplier> TRACK = Pref.b("TRACK", false);
			final Pref<BooleanSupplier> FOLDER = Pref.b("FOLDER", false);
			private final MediaSessionCallback cb =
					getActivity().getMediaServiceBinder().getMediaSessionCallback();
			private final Item item;

			PrefStore(Item item) {
				this.item = item;
				MediaPrefs prefs = item.getPrefs();
				BrowsableItem p = item.getParent();
				boolean set = false;

				try (PreferenceStore.Edit edit = editPreferenceStore()) {
					if (prefs.hasPref(MediaPrefs.SPEED)) {
						edit.setBooleanPref(TRACK, true);
						edit.setFloatPref(MediaPrefs.SPEED, prefs.getFloatPref(MediaPrefs.SPEED));
						set = true;
					} else {
						edit.setBooleanPref(TRACK, false);
					}

					if (p != null) {
						prefs = p.getPrefs();

						if (prefs.hasPref(MediaPrefs.SPEED)) {
							edit.setBooleanPref(FOLDER, true);

							if (!set) {
								edit.setFloatPref(MediaPrefs.SPEED, prefs.getFloatPref(MediaPrefs.SPEED));
								set = true;
							}
						} else {
							edit.setBooleanPref(FOLDER, false);
						}
					} else {
						edit.setBooleanPref(FOLDER, false);
					}

					if (!set) edit.setFloatPref(MediaPrefs.SPEED,
							cb.getPlaybackControlPrefs().getFloatPref(MediaPrefs.SPEED));
				}
			}

			void apply() {
				BrowsableItem p = item.getParent();
				boolean set = false;

				if (getBooleanPref(TRACK)) {
					item.getPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
					set = true;
				} else {
					item.getPrefs().removePref(MediaPrefs.SPEED);
				}

				if (p != null) {
					if (getBooleanPref(FOLDER)) {
						p.getPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
						set = true;
					} else {
						p.getPrefs().removePref(MediaPrefs.SPEED);
					}
				}

				if (!set) {
					cb.getPlaybackControlPrefs()
							.applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
				}
			}

			@Override
			public void applyFloatPref(boolean removeDefault, Pref<? extends DoubleSupplier> pref,
																 float value) {
				if (value == 0.0f) value = 0.1f;
				super.applyFloatPref(removeDefault, pref, value);
				if (cb.isPlaying()) cb.onSetPlaybackSpeed(value);
			}
		}
	}

	private final class TimerMenuHandler extends BasicPreferenceStore
			implements OverlayMenu.CloseHandler {
		private final Pref<IntSupplier> H = Pref.i("H", 0);
		private final Pref<IntSupplier> M = Pref.i("M", 0);
		private final MainActivityDelegate activity;
		private boolean changed;
		private boolean closed;

		TimerMenuHandler(MainActivityDelegate activity) {
			this.activity = activity;
		}

		void build(OverlayMenu.Builder b) {
			PreferenceSet set = new PreferenceSet();
			int time = activity.getMediaSessionCallback().getPlaybackTimer();

			if (time > 0) {
				int h = time / 3600;
				int m = (time - h * 3600) / 60;
				applyIntPref(H, h);
				applyIntPref(M, m);
			}

			set.addIntPref(o -> {
				o.title = R.string.hours;
				o.store = this;
				o.pref = H;
				o.seekMin = 0;
				o.seekMax = 12;
			});
			set.addIntPref(o -> {
				o.title = R.string.minutes;
				o.store = this;
				o.pref = M;
				o.seekMin = 0;
				o.seekMax = 60;
				o.seekScale = 5;
			});

			set.addToMenu(b, true);
			b.setCloseHandlerHandler(this);
			changed = false;
			startTimer();
		}

		@Override
		public void applyIntPref(boolean removeDefault, Pref<? extends IntSupplier> pref, int value) {
			super.applyIntPref(removeDefault, pref, value);
			changed = true;
			startTimer();
		}

		@Override
		public void menuClosed(OverlayMenu menu) {
			closed = true;
			if (!changed) return;
			int h = getIntPref(H);
			int m = getIntPref(M);
			activity.getMediaSessionCallback().setPlaybackTimer(h * 3600 + m * 60);
			checkPlaybackTimer(activity);
		}

		private void startTimer() {
			activity.postDelayed(() -> {
				if (!closed) getMenu(getActivity()).hide();
			}, 60000);
		}
	}

	private int getStartDelay() {
		return (prefs == null) ? 0 : prefs.getVideoControlStartDelayPref() * 1000;
	}

	private int getTouchDelay() {
		return (prefs == null) ? 5000 : prefs.getVideoControlTouchDelayPref() * 1000;
	}

	private int getSeekDelay() {
		return (prefs == null) ? 3000 : prefs.getVideoControlSeekDelayPref() * 1000;
	}

	private final class HideTimer implements Runnable {
		final MainActivityDelegate activity;
		final int delay;
		final boolean seekMode;
		final View[] views;

		HideTimer(MainActivityDelegate activity, int delay, boolean seekMode, View... views) {
			this.activity = activity;
			this.delay = delay;
			this.seekMode = seekMode;
			this.views = views;
		}

		@Override
		public void run() {
			if ((hideTimer != this) || ((mask & MASK_VIDEO_MODE) == 0)) return;

			VideoView vv = controlsHost;
			if (ControlPanelView.this.hasFocus() ||
					((vv != null) && vv.getControls().hasButtonFocus())) {
				hideTimer = new HideTimer(activity, delay, seekMode, views);
				activity.postDelayed(hideTimer, delay);
				return;
			}

			if (activity.getPrefs().getSysBarsOnVideoTouchPref()) activity.setFullScreen(true);
			ControlPanelView.super.setVisibility(GONE);
			notifyControlPanelVisibility();
			hideVideoControls(true);

			for (View v : views) {
				if (v != null) hideFab(v);
			}
		}
	}
}
