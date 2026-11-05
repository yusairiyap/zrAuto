package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.annotation.SuppressLint;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.transition.TransitionConfig;
import me.aap.fermata.ui.transition.TransitionConfig.Element;
import me.aap.fermata.ui.transition.TransitionController;
import me.aap.fermata.ui.transition.TransitionDemos;
import me.aap.fermata.ui.transition.TransitionFacts;
import me.aap.fermata.ui.transition.TransitionView;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.SwitchStyle;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * Settings &gt; Playback &gt; Transition screen: the screen that comes over the app for a moment when a new song
 * starts. One page, two sets (the phone's and the car screen's, picked with the two pills), and at the top a
 * live preview of the screen that is itself the editor: tap an element to pick it, drag it to move it, drag its
 * corner or pinch to size it. Under it are the options of the set, then the options of the picked element
 * (looks, fill and edge, its entrance and exit). Everything is saved as it changes. Never run on a device.
 */
public class TransitionSettingsFragment extends ReorderListFragment implements TransitionView.Editor {
	private static final int[] SWATCHES = {0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF9800, 0xFFF44336, 0xFFE91E63,
			0xFF9C27B0, 0xFF2196F3, 0xFF00BCD4, 0xFF4CAF50, 0xFF9E9E9E};

	private boolean car;
	private boolean carInput;
	private TransitionConfig cfg;
	private int selectedId;
	@Nullable
	private TransitionView preview;
	private boolean playing;

	@Override
	public int getFragmentId() {
		return R.id.transition_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.tr_settings);
	}

	@Override
	public void setInput(Object input) {
		carInput = Boolean.TRUE.equals(input);
		if (getView() == null) car = carInput;
		else switchSet(carInput);
	}

	// ---- No rows ----

	@Override
	protected List<Row> loadRows() {
		return Collections.emptyList();
	}

	@Override
	protected void saveOrder(List<String> keys) {
	}

	@Override
	protected void setChecked(String key, boolean checked) {
	}

	@Override
	protected void showRowMenu(Row row) {
	}

	@Override
	protected void onReset() {
		TransitionConfig.reset(car);
		TransitionController.pictureFile(car).delete();
		selectedId = 0;
		cfg = TransitionConfig.load(car);
		if (preview != null) {
			preview.setConfig(cfg);
			preview.setSelected(0);
			preview.setPicture(null);
		}
		if (wide) openPanel(P_GENERAL);
		else closeSheet();
	}

	@Override
	protected int resetTitle() {
		return R.string.tr_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.tr_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.tr_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	// ---- The page: a full preview, and the options beside it (wide) or in a sheet over it (phone) ----

	private static final int P_GENERAL = 0, P_ELEMENT = 1, P_ADD = 2;
	private boolean wide;
	private int panelMode = P_GENERAL;
	private boolean sheetOpen;
	@Nullable
	private FrameLayout root;
	@Nullable
	private Stage stage;
	@Nullable
	private FrameLayout sidePanel;
	@Nullable
	private FrameLayout sheet;
	@Nullable
	private LinearLayout panelCard;
	private final List<Runnable> sync = new ArrayList<>();
	private boolean onCar;
	private int panelW;
	private View stripView, rightHolder;
	private FrameLayout.LayoutParams hintLp, selLp, rightLp, stripLp;
	private LinearLayout.LayoutParams sideLp;
	@Nullable
	private android.widget.ImageButton playBtn;
	@Nullable
	private Runnable previewEnd;
	@Nullable
	private ChipFlowLayout panelChips;
	@Nullable
	private android.widget.HorizontalScrollView panelChipScroll;
	@Nullable
	private LinearLayout panelBody;
	@Nullable
	private android.widget.ScrollView panelScroll;
	@Nullable
	private TextView hintView;
	@Nullable
	private LinearLayout selBar;
	@Nullable
	private TextView selName;
	@Nullable
	private android.animation.ValueAnimator insetAnim;

	/** Keeps the shape of the screen shown, as large as the room allows, between the chips and the hint. */
	private final class Stage extends FrameLayout {
		private float aspect = 16f / 9f;
		/** Room taken at the bottom by the open sheet, px. */
		int inset;

		Stage(Context c) {
			super(c);
		}

		void setInset(int px) {
			if (inset == px) return;
			inset = px;
			requestLayout();
		}

		void setAspect(float a) {
			aspect = a;
			requestLayout();
		}

		@Override
		protected void onMeasure(int wSpec, int hSpec) {
			int w = MeasureSpec.getSize(wSpec);
			int h = MeasureSpec.getSize(hSpec);
			setMeasuredDimension(w, h);
			int fw = Math.min(w - dp(24), Math.round((h - dp(52 + 52) - inset) * aspect));
			fw = Math.max(dp(80), fw);
			int fh = Math.round(fw / aspect);
			for (int i = 0; i < getChildCount(); i++) {
				getChildAt(i).measure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY),
						MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY));
			}
		}

		@Override
		protected void onLayout(boolean changed, int l, int t, int r, int b) {
			int w = r - l;
			int h = b - t;
			for (int i = 0; i < getChildCount(); i++) {
				View c = getChildAt(i);
				int cw = c.getMeasuredWidth();
				int ch = c.getMeasuredHeight();
				int left = (w - cw) / 2;
				int room = h - dp(52 + 52) - inset;
				int top = dp(52) + Math.max(0, (room - ch) / 2);
				c.layout(left, top, left + cw, top + ch);
			}
		}
	}

	/** The set being edited, loaded on first use. */
	private void ensureCfg() {
		if (cfg != null) return;
		if (!carInput) {
			MainActivityDelegate d = getActivityDelegate();
			car = (d != null) && d.isCarActivity();
		}
		cfg = TransitionConfig.load(car);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable android.view.ViewGroup container,
													 @Nullable android.os.Bundle state) {
		Context ctx = inflater.getContext();
		initPalette(ctx);
		ensureCfg();
		MainActivityDelegate d = getActivityDelegate();
		onCar = (d != null) && d.isCarActivity();
		wide = (ctx.getResources().getConfiguration().screenWidthDp >= 720) || onCar;
		// The column beside the preview: a share of the screen's width, dragged by the strip on its edge.
		int scrW = ctx.getResources().getDisplayMetrics().widthPixels;
		int pct = (d == null) ? 40 : d.getPrefs().getIntPref(MainActivityPrefs.TRANSITION_PANEL_PERCENT);
		panelW = clampPanel(Math.round(scrW * pct / 100f), scrW);

		root = new FrameLayout(palette);
		root.setClipChildren(false);
		LinearLayout row = new LinearLayout(palette);
		row.setClipChildren(false);
		row.setOrientation(LinearLayout.HORIZONTAL);
		root.addView(row, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		stage = new Stage(palette);
		FrameLayout scene = new FrameLayout(palette);
		// A stand-in for the app behind the screen, so a clear or dim one can be told from a solid one.
		GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2B5876, 0xFF4E4376});
		bg.setCornerRadius(dp(14));
		scene.setBackground(bg);
		scene.setClipToOutline(true);
		scene.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
		preview = new TransitionView(palette, cfg);
		preview.setSample(true);
		preview.setEditor(this);
		preview.setPicture((cfg.bg == TransitionConfig.BG_PICTURE) ? TransitionController.loadPicture(car) : null);
		scene.addView(preview, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		stage.addView(scene, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		row.addView(stage, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
		updateAspect();

		TextView hint = text(R.string.tr_edit_hint, 12f, textSecondary, false);
		hint.setGravity(Gravity.CENTER_VERTICAL);
		hint.setSingleLine(true);
		hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
		hintView = hint;
		FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(MATCH_PARENT, dp(40), Gravity.BOTTOM);
		hlp.bottomMargin = dp(6);
		hlp.setMarginStart(dp(12));
		// Room for the app's own floating button at the right on a phone.
		hlp.setMarginEnd(wide ? panelW + dp(12) : dp(84));
		hintLp = hlp;
		root.addView(hint, hlp);

		// What is picked: a small bar in the same place, so tapping an element never covers the preview.
		selBar = new LinearLayout(palette);
		selBar.setGravity(Gravity.CENTER_VERTICAL);
		selBar.setPadding(dp(12), 0, dp(4), 0);
		selBar.setElevation(dp(4));
		selBar.setBackground(me.aap.fermata.ui.view.MenuStyle.panel(selBar, paletteColor(R.attr.musicPanelFill), dp(22)));
		selName = text(0, 14, textPrimary, true);
		selName.setSingleLine(true);
		selName.setEllipsize(android.text.TextUtils.TruncateAt.END);
		selBar.addView(selName, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		selBar.addView(barButton(R.drawable.settings, R.string.tr_edit, () -> {
			if (selected() != null) openPanel(P_ELEMENT);
		}));
		selBar.addView(barButton(R.drawable.cover_hidden, R.string.tr_show, () -> {
			Element e = selected();
			if (e == null) return;
			e.visible = !e.visible;
			changed();
			updateSelBar();
		}));
		selBar.addView(barButton(R.drawable.delete, R.string.tr_remove, () -> {
			if (selected() == null) return;
			UiUtils.showQuestion(requireContext(), R.string.tr_remove, R.string.tr_remove_question, R.drawable.delete)
					.onSuccess(ok -> removeSelected());
		}));
		selBar.addView(barButton(me.aap.utils.R.drawable.close, R.string.close, () -> {
			if (sheetOpen) closeSheet();
			selectedId = 0;
			if (preview != null) preview.setSelected(0);
			updateSelBar();
		}));
		selBar.setVisibility(View.GONE);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(MATCH_PARENT, dp(44), Gravity.BOTTOM);
		blp.bottomMargin = dp(6);
		blp.setMarginStart(dp(8));
		blp.setMarginEnd(wide ? panelW + dp(8) : dp(84));
		selLp = blp;
		root.addView(selBar, blp);

		if (wide) {
			sidePanel = new FrameLayout(palette);
			sidePanel.setClipChildren(false);
			LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(panelW, MATCH_PARENT);
			slp.setMargins(0, dp(4), dp(8), dp(8));
			sideLp = slp;
			row.addView(sidePanel, slp);
		} else {
			sheet = new FrameLayout(palette);
			sheet.setClipChildren(false);
			sheet.setVisibility(View.GONE);
			FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(MATCH_PARENT, dp(260), Gravity.BOTTOM);
			slp.setMargins(dp(8), 0, dp(8), dp(6));
			root.addView(sheet, slp);
			root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
				if (sheet == null) return;
				int h = Math.round((b - t) * 0.46f);
				android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
				if (lp.height != h) {
					lp.height = h;
					sheet.setLayoutParams(lp);
					if (sheetOpen && (stage != null)) stage.setInset(h + dp(6));
				}
			});
		}

		// The set, and the preview's own buttons: small round chips, so nothing is cut off on a phone.
		LinearLayout left = new LinearLayout(palette);
		setChips = left;
		fillSetChips();
		FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP | Gravity.START);
		llp.setMargins(dp(8), dp(6), 0, 0);
		root.addView(left, llp);

		LinearLayout right = new LinearLayout(palette);
		playBtn = roundChip(R.drawable.play, true, getString(R.string.tr_play), this::playPreview);
		right.addView(playBtn, chipLp(false));
		right.addView(roundChip(R.drawable.home_add, false, getString(R.string.tr_add_title), () -> openPanel(P_ADD)), chipLp(true));
		right.addView(roundChip(R.drawable.settings, false, getString(R.string.tr_general), () -> {
			selectedId = 0;
			if (preview != null) preview.setSelected(0);
			updateSelBar();
			openPanel(P_GENERAL);
		}), chipLp(true));
		FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP | Gravity.END);
		rlp.setMargins(0, dp(6), wide ? panelW + dp(8) : dp(8), 0);
		rightLp = rlp;
		rightHolder = right;
		root.addView(right, rlp);
		if (wide) addPanelStrip(d);

		// What plays now is on the preview, with its cover (nothing playing: the samples), and follows the song.
		refreshSong();
		if (d != null) d.getMediaServiceBinder().addBroadcastListener(songListener);

		if (wide) openPanel(P_GENERAL);
		return root;
	}

	@Nullable
	private LinearLayout setChips;

	private final me.aap.fermata.media.service.FermataServiceUiBinder.Listener songListener =
			new me.aap.fermata.media.service.FermataServiceUiBinder.Listener() {
				@Override
				public void onPlayableChanged(me.aap.fermata.media.lib.MediaLib.PlayableItem oldItem,
																			me.aap.fermata.media.lib.MediaLib.PlayableItem newItem) {
					// The YouTube engine names the video a moment after the session changed: look again once more.
					App.get().getHandler().post(TransitionSettingsFragment.this::refreshSong);
					App.get().getHandler().postDelayed(TransitionSettingsFragment.this::refreshSong, 900);
				}
			};

	/** Puts the song that plays now (title, channel, playlist and cover) on the preview. */
	private void refreshSong() {
		MainActivityDelegate d = getActivityDelegate();
		TransitionView v = preview;
		if ((d == null) || (v == null)) return;
		me.aap.fermata.media.lib.MediaLib.PlayableItem pi = me.aap.fermata.ui.home.HomeArt.displayItem(d);
		if (pi != null) TransitionController.loadInto(d, pi, v);
	}

	/** The Phone / Android Auto toggle: icons only on a phone (names beside them when there is room). */
	private void fillSetChips() {
		LinearLayout l = setChips;
		if (l == null) return;
		l.removeAllViews();
		l.addView(roundChip(R.drawable.home_page, !car, getString(R.string.home_settings_tab_phone), () -> switchSet(false)),
				chipLp(false));
		l.addView(roundChip(R.drawable.car, car, getString(R.string.home_settings_tab_aa), () -> switchSet(true)), chipLp(true));
	}

	private LinearLayout.LayoutParams chipLp(boolean gap) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
		if (gap) lp.setMarginStart(dp(6));
		return lp;
	}

	/** A 40dp round button for the preview's corners: filled with the accent when it is the one that matters or is picked. */
	private android.widget.ImageButton roundChip(@DrawableRes int icon, boolean on, String desc, Runnable click) {
		android.widget.ImageButton b = new android.widget.ImageButton(palette);
		b.setImageResource(icon);
		b.setImageTintList(ColorStateList.valueOf(on ? onAccent : textPrimary));
		GradientDrawable fill = new GradientDrawable();
		fill.setShape(GradientDrawable.OVAL);
		fill.setColor(on ? accent : (paletteColor(R.attr.musicPanelFill) | 0xFF000000));
		fill.setStroke(dp(1), 0x33808080);
		b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(ripple), fill, null));
		b.setContentDescription(desc);
		b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
		b.setOnClickListener(v -> click.run());
		return b;
	}

	/** A small chip for the options card's header: 34dp high, the picked one filled with the accent. */
	private TextView smallChip(@DrawableRes int icon, CharSequence label, boolean on, Runnable click) {
		TextView t = text(0, 12.5f, on ? onAccent : textPrimary, true);
		t.setText(label);
		t.setSingleLine(true);
		t.setGravity(Gravity.CENTER_VERTICAL);
		t.setMinHeight(dp(34));
		// The same room either side (an icon-only chip is a circle).
		int pd = dp(label.length() == 0 ? 10 : 12);
		t.setPadding(pd, 0, pd, 0);
		if (label.length() == 0) {
			t.setMinWidth(dp(34));
			t.setGravity(Gravity.CENTER);
		}
		android.graphics.drawable.Drawable ic = androidx.appcompat.content.res.AppCompatResources.getDrawable(palette, icon);
		if (ic != null) {
			ic = ic.mutate();
			ic.setBounds(0, 0, dp(18), dp(18));
			ic.setTintList(ColorStateList.valueOf(on ? onAccent : textPrimary));
			t.setCompoundDrawablesRelative(ic, null, null, null);
			t.setCompoundDrawablePadding(label.length() == 0 ? 0 : dp(6));
		}
		GradientDrawable fill = rounded(on ? accent : 0x1F808080, 17);
		t.setBackground(pressable(fill));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> click.run());
		return t;
	}

	private android.widget.ImageButton barButton(@DrawableRes int icon, @StringRes int desc, Runnable click) {
		android.widget.ImageButton b = new android.widget.ImageButton(palette);
		b.setImageResource(icon);
		b.setImageTintList(ColorStateList.valueOf(textPrimary));
		b.setBackground(pressable(rounded(0x00000000, 18)));
		b.setContentDescription(getString(desc));
		b.setOnClickListener(v -> click.run());
		b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
		return b;
	}

	/** Shows the small bar for the picked element (phone), or the hint when nothing is picked. */
	private void updateSelBar() {
		if (wide || (selBar == null) || (hintView == null)) return;
		Element e = selected();
		boolean on = (e != null) && !sheetOpen;
		selBar.setVisibility(on ? View.VISIBLE : View.GONE);
		hintView.setVisibility((e == null) && !sheetOpen ? View.VISIBLE : View.GONE);
		if ((e != null) && (selName != null)) selName.setText(nameOf(e));
	}

	private void removeSelected() {
		Element e = selected();
		if (e == null) return;
		cfg.remove(e);
		selectedId = 0;
		cfg.save(car);
		if (preview != null) {
			preview.setSelected(0);
			preview.refresh();
		}
		if (wide) openPanel(P_GENERAL);
		else closeSheet();
		updateSelBar();
	}

	private void animateInset(int to) {
		Stage st = stage;
		if (st == null) return;
		if (insetAnim != null) insetAnim.cancel();
		insetAnim = android.animation.ValueAnimator.ofInt(st.inset, to);
		insetAnim.setDuration(220);
		insetAnim.addUpdateListener(a -> st.setInset((int) a.getAnimatedValue()));
		insetAnim.start();
	}

	/** The shape of the real screen: this window's for the phone's set, the car screen's as last seen for the car's. */
	private void updateAspect() {
		Stage st = stage;
		if (st == null) return;
		if (car && !onCar) {
			float a = TransitionConfig.carAspect();
			st.setAspect((a > 0.5f) ? a : 2f);
			return;
		}
		MainActivityDelegate d = getActivityDelegate();
		View host = (d == null) ? null : TransitionController.overlayHost(d);
		if ((host != null) && (host.getWidth() > 0) && (host.getHeight() > 0)) {
			st.setAspect((float) host.getWidth() / host.getHeight());
			return;
		}
		android.util.DisplayMetrics dm = palette.getResources().getDisplayMetrics();
		st.setAspect((float) dm.widthPixels / dm.heightPixels);
	}

	private void playPreview() {
		TransitionView v = preview;
		if (v == null) return;
		if (playing) {
			stopPreview();
			return;
		}
		playing = true;
		setPlayIcon();
		if (cfg.bg == TransitionConfig.BG_PICTURE) v.setPicture(TransitionController.loadPicture(car));
		v.playIn();
		long stay = Math.min(2500, cfg.holdTenths * 100L) + cfg.inMs;
		previewEnd = () -> v.playOut(() -> {
			v.showNow();
			playing = false;
			previewEnd = null;
			setPlayIcon();
		});
		v.postDelayed(previewEnd, stay);
	}

	/** The play button again: stops the preview where it is and shows the screen at rest. */
	private void stopPreview() {
		TransitionView v = preview;
		if (v != null) {
			if (previewEnd != null) v.removeCallbacks(previewEnd);
			v.stop();
		}
		previewEnd = null;
		playing = false;
		setPlayIcon();
	}

	private void setPlayIcon() {
		android.widget.ImageButton b = playBtn;
		if (b != null) {
			b.setImageResource(playing ? R.drawable.pause : R.drawable.play);
			b.setContentDescription(getString(R.string.tr_play));
		}
	}

	private void switchSet(boolean forCar) {
		if (forCar == car) return;
		car = forCar;
		selectedId = 0;
		cfg = TransitionConfig.load(car);
		if (preview != null) {
			preview.setConfig(cfg);
			preview.setSelected(0);
			preview.setPicture(cfg.bg == TransitionConfig.BG_PICTURE ? TransitionController.loadPicture(car) : null);
		}
		fillSetChips();
		updateAspect();
		updateSelBar();
		closeSheet();
		if (wide) openPanel(P_GENERAL);
	}

	@Override
	public boolean onBackPressed() {
		if (sheetOpen) {
			closeSheet();
			return true;
		}
		if (!wide && (selectedId != 0)) {
			selectedId = 0;
			if (preview != null) preview.setSelected(0);
			updateSelBar();
			return true;
		}
		return super.onBackPressed();
	}

	@Override
	public void onDestroyView() {
		MainActivityDelegate dd = getActivityDelegate();
		if (dd != null) dd.getMediaServiceBinder().removeBroadcastListener(songListener);
		cfg = null;
		preview = null;
		root = null;
		stage = null;
		sidePanel = null;
		stripView = null;
		rightHolder = null;
		sheet = null;
		panelCard = null;
		setChips = null;
		hintView = null;
		playBtn = null;
		previewEnd = null;
		playing = false;
		panelChips = null;
		panelChipScroll = null;
		panelBody = null;
		panelScroll = null;
		selBar = null;
		selName = null;
		if (insetAnim != null) insetAnim.cancel();
		insetAnim = null;
		sync.clear();
		sheetOpen = false;
		super.onDestroyView();
	}

	// ---- The panel ----

	/** Shows the panel for {@code mode}: in the side column (wide), or as a sheet over the preview (phone). */
	private void openPanel(int mode) {
		panelMode = mode;
		boolean shown = (panelCard != null) && (wide ? (panelCard.getParent() != null) : sheetOpen);
		if (shown) {
			// Already up: only the content changes, in place (no sliding card, no flicker).
			refreshPanel();
			updateSelBar();
			return;
		}
		LinearLayout card = createPanelCard();
		panelCard = card;
		refreshPanel();
		if (wide) {
			FrameLayout host = sidePanel;
			if (host == null) return;
			host.removeAllViews();
			host.addView(card, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		} else {
			FrameLayout host = sheet;
			if (host == null) return;
			host.removeAllViews();
			host.addView(card, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			host.setVisibility(View.VISIBLE);
			sheetOpen = true;
			updateSelBar();
			animateInset(host.getLayoutParams().height + dp(6));
			host.setTranslationY(dp(220));
			host.setAlpha(0f);
			host.animate().translationY(0f).alpha(1f).setDuration(240)
					.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
		}
	}

	private void closeSheet() {
		FrameLayout host = sheet;
		if ((host == null) || !sheetOpen) return;
		sheetOpen = false;
		panelCard = null;
		host.animate().translationY(dp(220)).alpha(0f).setDuration(200)
				.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f))
				.withEndAction(() -> {
					if (!sheetOpen) {
						host.setVisibility(View.GONE);
						host.removeAllViews();
					}
				}).start();
		animateInset(0);
		updateSelBar();
	}

	private int clampPanel(int w, int scrW) {
		return Math.max(Math.round(scrW * 0.22f), Math.min(Math.round(scrW * 0.65f), w));
	}

	private void setPanelW(int w) {
		if (w == panelW) return;
		panelW = w;
		if (sideLp != null) {
			sideLp.width = w;
			if (sidePanel != null) sidePanel.setLayoutParams(sideLp);
		}
		if (hintLp != null) hintLp.setMarginEnd(w + dp(12));
		if (selLp != null) selLp.setMarginEnd(w + dp(8));
		if (rightLp != null) rightLp.setMarginEnd(w + dp(8));
		if (stripLp != null) stripLp.setMarginEnd(w + dp(8));
		if (hintView != null) hintView.setLayoutParams(hintLp);
		if (selBar != null) selBar.setLayoutParams(selLp);
		if (stripView != null) stripView.setLayoutParams(stripLp);
		View r = (rightLp == null) ? null : (View) rightHolder;
		if (r != null) r.setLayoutParams(rightLp);
	}

	/** The grip on the options column's left edge, drawn like the Settings list's divider: drag it to resize the column. */
	@SuppressLint("ClickableViewAccessibility")
	private void addPanelStrip(@Nullable MainActivityDelegate d) {
		final me.aap.fermata.ui.view.SettingsStyle.Colors colors = new me.aap.fermata.ui.view.SettingsStyle.Colors(palette);
		View strip = new View(palette) {
			private final android.graphics.Paint grip = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
			private final android.graphics.RectF r = new android.graphics.RectF();
			private float startX;
			private int startW;
			private boolean dragging;

			{
				grip.setColor(colors.textSecondary);
				grip.setAlpha(150);
			}

			@Override
			protected void onDraw(android.graphics.Canvas c) {
				float h = colors.dp(dragging ? 56 : 44);
				float w = colors.dp(dragging ? 6 : 4);
				float x = getWidth() / 2f;
				r.set(x - w / 2f, (getHeight() - h) / 2f, x + w / 2f, (getHeight() + h) / 2f);
				c.drawRoundRect(r, w / 2f, w / 2f, grip);
			}

			@Override
			public boolean onTouchEvent(android.view.MotionEvent e) {
				int scrW = getResources().getDisplayMetrics().widthPixels;
				switch (e.getActionMasked()) {
					case android.view.MotionEvent.ACTION_DOWN -> {
						startX = e.getRawX();
						startW = panelW;
						dragging = true;
						if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
						invalidate();
						return true;
					}
					case android.view.MotionEvent.ACTION_MOVE -> {
						if (dragging) setPanelW(clampPanel(startW - Math.round(e.getRawX() - startX), scrW));
						return true;
					}
					case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
						if (dragging) {
							dragging = false;
							if (d != null) {
								d.getPrefs().applyIntPref(MainActivityPrefs.TRANSITION_PANEL_PERCENT,
										Math.round(panelW * 100f / Math.max(1, scrW)));
							}
							invalidate();
						}
						return true;
					}
					default -> {
						return super.onTouchEvent(e);
					}
				}
			}
		};
		strip.setContentDescription(getString(R.string.tr_resize_panel));
		stripLp = new FrameLayout.LayoutParams(dp(22), MATCH_PARENT, Gravity.END);
		stripLp.setMargins(0, dp(60), panelW + dp(8), dp(12));
		stripView = strip;
		root.addView(strip, stripLp);
	}

	/** Moves the options card with a finger on its grip; never off the page. */
	private final class PanelDrag implements View.OnTouchListener {
		private final View card;
		private float downX, downY, startTx, startTy, minTx, maxTx, minTy, maxTy;
		private boolean moved;

		PanelDrag(View card) {
			this.card = card;
		}

		@Override
		public boolean onTouch(View v, android.view.MotionEvent e) {
			switch (e.getActionMasked()) {
				case android.view.MotionEvent.ACTION_DOWN -> {
					v.getParent().requestDisallowInterceptTouchEvent(true);
					downX = e.getRawX();
					downY = e.getRawY();
					startTx = card.getTranslationX();
					startTy = card.getTranslationY();
					moved = false;
					View r = root;
					if (r == null) return true;
					int[] rl = new int[2];
					int[] cl = new int[2];
					r.getLocationOnScreen(rl);
					card.getLocationOnScreen(cl);
					float left = cl[0] - rl[0] - startTx;
					float top = cl[1] - rl[1] - startTy;
					minTx = -left - card.getWidth() + dp(90);
					maxTx = r.getWidth() - left - dp(90);
					minTy = -top;
					maxTy = r.getHeight() - top - dp(48);
					return true;
				}
				case android.view.MotionEvent.ACTION_MOVE -> {
					float dx = e.getRawX() - downX;
					float dy = e.getRawY() - downY;
					if (!moved && (Math.hypot(dx, dy) < dp(6))) return true;
					moved = true;
					card.setTranslationX(Math.max(minTx, Math.min(maxTx, startTx + dx)));
					card.setTranslationY(Math.max(minTy, Math.min(maxTy, startTy + dy)));
					return true;
				}
				case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
					if (!moved) {
						card.animate().translationX(0f).translationY(0f).setDuration(180).start();
					} else if (!wide && (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) &&
							((card.getTranslationY() - startTy) > dp(150))) {
						closeSheet();
					}
					return true;
				}
				default -> {
					return true;
				}
			}
		}
	}

	/** The card itself: a header of chips (what to edit) with a close button, and a scrolling body. Made once; its content is refreshed in place. */
	private LinearLayout createPanelCard() {
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClickable(true);
		// Beside the preview the column is flat like the Settings pages (a shadow showed as a dark edge in a light theme).
		card.setElevation(wide ? 0 : dp(8));
		card.setBackground(me.aap.fermata.ui.view.MenuStyle.panel(card, paletteColor(R.attr.musicPanelFill), dp(22)));
		card.setPadding(dp(8), dp(8), dp(8), dp(4));

		LinearLayout head = new LinearLayout(palette);
		head.setGravity(Gravity.CENTER_VERTICAL);
		panelChipScroll = new android.widget.HorizontalScrollView(palette);
		panelChipScroll.setHorizontalScrollBarEnabled(false);
		panelChips = new ChipFlowLayout(palette);
		panelChips.setOneRow(true);
		panelChips.setPadding(dp(2), dp(2), dp(2), dp(2));
		panelChipScroll.addView(panelChips);
		head.addView(panelChipScroll, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		android.widget.ImageButton close = new android.widget.ImageButton(palette);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(textPrimary));
		GradientDrawable cb = new GradientDrawable();
		cb.setShape(GradientDrawable.OVAL);
		cb.setColor(0x1F808080);
		close.setBackground(cb);
		close.setContentDescription(getString(R.string.close));
		close.setOnClickListener(v -> {
			if (wide) {
				selectedId = 0;
				if (preview != null) preview.setSelected(0);
				openPanel(P_GENERAL);
			} else {
				closeSheet();
			}
		});
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(34), dp(34));
		clp.setMarginStart(dp(8));
		head.addView(close, clp);

		// A grip to move the card around by (a tap on it puts the card back; a hard pull down closes the sheet on a phone).
		FrameLayout gripBox = new FrameLayout(palette);
		View grip = new View(palette);
		GradientDrawable gd = new GradientDrawable();
		gd.setColor(0x66808080);
		gd.setCornerRadius(dp(2));
		grip.setBackground(gd);
		gripBox.addView(grip, new FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER));
		gripBox.setContentDescription(getString(R.string.tr_move_panel));
		gripBox.setOnTouchListener(new PanelDrag(card));
		if (!wide) card.addView(gripBox, new LinearLayout.LayoutParams(MATCH_PARENT, dp(18)));
		card.addView(head, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		panelScroll = new android.widget.ScrollView(palette);
		panelScroll.setVerticalScrollBarEnabled(false);
		panelBody = new LinearLayout(palette);
		panelBody.setOrientation(LinearLayout.VERTICAL);
		panelBody.setPadding(0, 0, 0, dp(10));
		panelScroll.addView(panelBody, new android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		card.addView(panelScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		me.aap.fermata.action.CarNav.markScope(card, wide ? null : this::closeSheet);
		return card;
	}

	/** Rebuilds the chips' look and the body for {@link #panelMode} without touching the card. */
	private void refreshPanel() {
		ChipFlowLayout chips = panelChips;
		LinearLayout body = panelBody;
		if ((chips == null) || (body == null)) return;
		sync.clear();

		chips.removeAllViews();
		TextView picked = null;
		TextView general = smallChip(R.drawable.settings, getString(R.string.tr_general), panelMode == P_GENERAL, () -> {
			selectedId = 0;
			if (preview != null) preview.setSelected(0);
			updateSelBar();
			openPanel(P_GENERAL);
		});
		chips.addView(general);
		if (panelMode == P_GENERAL) picked = general;
		for (Element e : cfg.elements) {
			boolean on = (panelMode == P_ELEMENT) && (e.id == selectedId);
			TextView c = smallChip(iconOf(e.type), nameOf(e), on, () -> pick(e.id));
			c.setAlpha(e.visible ? 1f : 0.5f);
			chips.addView(c);
			if (on) picked = c;
		}
		TextView add = smallChip(R.drawable.home_add, "", panelMode == P_ADD, () -> openPanel(P_ADD));
		chips.addView(add);
		if (panelMode == P_ADD) picked = add;
		for (int i = 1; i < chips.getChildCount(); i++) {
			chips.getChildAt(i).setLayoutParams(new android.view.ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		}
		TextView pk = picked;
		android.widget.HorizontalScrollView hsv = panelChipScroll;
		if ((pk != null) && (hsv != null)) {
			// Only when the picked chip is not wholly in view, and then to its edge (never leaving it cut off).
			hsv.post(() -> {
				int l = pk.getLeft();
				int r = pk.getRight();
				int sx = hsv.getScrollX();
				if (l < sx) hsv.smoothScrollTo(Math.max(0, l - dp(4)), 0);
				else if (r > sx + hsv.getWidth()) hsv.smoothScrollTo(r - hsv.getWidth() + dp(4), 0);
			});
		}

		// The body fades its content over (about 120 ms) instead of a card sliding in.
		body.animate().cancel();
		body.removeAllViews();
		body.setAlpha(0.15f);
		switch (panelMode) {
			case P_ELEMENT -> {
				if (selected() == null) buildGeneral(body);
				else buildElement(body, selected());
			}
			case P_ADD -> buildAdd(body);
			default -> buildGeneral(body);
		}
		if (panelScroll != null) panelScroll.scrollTo(0, 0);
		body.animate().alpha(1f).setDuration(130).start();
		runSync();
	}

	private void pick(int id) {
		selectedId = id;
		if (preview != null) preview.setSelected(id);
		if (id == 0) {
			if (wide) openPanel(P_GENERAL);
			else closeSheet();
			return;
		}
		openPanel(P_ELEMENT);
	}

	// ---- Editing in the preview ----

	@Override
	public void onPicked(int id) {
		if (wide) {
			pick(id);
			return;
		}
		// A phone: a touch only picks (so it can be dragged at once); the options open on a second tap or Edit.
		selectedId = id;
		if (preview != null) preview.setSelected(id);
		if (id == 0) {
			if (sheetOpen) closeSheet();
		} else if (sheetOpen) {
			openPanel(P_ELEMENT);
		}
		updateSelBar();
	}

	@Override
	public void onTapSelected(int id) {
		if (!wide && (selected() != null)) openPanel(P_ELEMENT);
	}

	@Override
	public void onEdited(Element e, boolean done) {
		if (panelMode == P_ELEMENT) runSync();
		if (done) cfg.save(car);
	}

	@Nullable
	private Element selected() {
		return (selectedId == 0) ? null : cfg.find(selectedId);
	}

	private String nameOf(Element e) {
		String n = getString(TransitionConfig.nameRes(e.type));
		int same = 0;
		int index = 0;
		for (Element o : cfg.elements) {
			if (o.type == e.type) {
				same++;
				if (o == e) index = same;
			}
		}
		return (same > 1) ? n + " " + index : n;
	}

	@DrawableRes
	private static int iconOf(int type) {
		return switch (type) {
			case TransitionConfig.T_ART -> R.drawable.home_image;
			case TransitionConfig.T_PLAYLIST -> R.drawable.playlist;
			case TransitionConfig.T_CHANNEL -> R.drawable.account;
			case TransitionConfig.T_NEXT -> R.drawable.up_next;
			case TransitionConfig.T_QUEUE -> R.drawable.queue_music;
			case TransitionConfig.T_CLOCK -> R.drawable.clock;
			case TransitionConfig.T_SOURCE -> R.drawable.download;
			case TransitionConfig.T_QUALITY -> R.drawable.quality;
			case TransitionConfig.T_PICTURE -> R.drawable.home_image;
			case TransitionConfig.T_SHAPE -> R.drawable.expand;
			case TransitionConfig.T_INFO -> R.drawable.battery;
			default -> R.drawable.title;
		};
	}

	private String[] strings(@StringRes int... ids) {
		String[] out = new String[ids.length];
		for (int i = 0; i < ids.length; i++) out[i] = getString(ids[i]);
		return out;
	}

	private void changed() {
		cfg.save(car);
		if (preview != null) preview.refresh();
		runSync();
	}

	private void elementChanged() {
		changed();
	}

	private void runSync() {
		for (Runnable r : new ArrayList<>(sync)) r.run();
	}

	// ---- What the panel holds ----

	/** Tighter rows on a phone and on the car screen (which has little room under the bars). */
	private boolean compact() {
		return !wide || onCar;
	}

	/** A section the user can fold: its title with a chevron, and the rows under it (open at first only when there is room). */
	private LinearLayout group(LinearLayout parent, @StringRes int title, boolean open) {
		LinearLayout head = new LinearLayout(palette);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.setPadding(dp(4), dp(compact() ? 10 : 14), dp(4), dp(compact() ? 4 : 6));
		head.setClickable(true);
		head.setFocusable(true);
		TextView t = text(title, 12.5f, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		head.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		android.widget.ImageView chev = new android.widget.ImageView(palette);
		chev.setImageResource(me.aap.utils.R.drawable.chevron_right);
		chev.setImageTintList(ColorStateList.valueOf(accent));
		chev.setRotation(open ? 90f : 0f);
		head.addView(chev, new LinearLayout.LayoutParams(dp(22), dp(22)));
		LinearLayout content = new LinearLayout(palette);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setVisibility(open ? View.VISIBLE : View.GONE);
		head.setOnClickListener(v -> {
			boolean show = content.getVisibility() != View.VISIBLE;
			content.setVisibility(show ? View.VISIBLE : View.GONE);
			chev.animate().rotation(show ? 90f : 0f).setDuration(150).start();
		});
		parent.addView(head);
		parent.addView(content, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return content;
	}

	private void buildAdd(LinearLayout p) {
		section(p, R.string.tr_section_elements);
		TextView hint = text(R.string.tr_elements_hint, 12.5f, textSecondary, false);
		hint.setPadding(dp(4), 0, dp(4), dp(6));
		p.addView(hint);
		int[] kinds = {TransitionConfig.T_TITLE, TransitionConfig.T_CHANNEL, TransitionConfig.T_ART,
				TransitionConfig.T_PLAYLIST, TransitionConfig.T_NEXT, TransitionConfig.T_QUEUE, TransitionConfig.T_CLOCK,
				TransitionConfig.T_SOURCE, TransitionConfig.T_QUALITY, TransitionConfig.T_PICTURE, TransitionConfig.T_SHAPE,
				TransitionConfig.T_INFO, TransitionConfig.T_TEXT};
		for (int kind : kinds) {
			actionRow(p, getString(R.string.tr_add_fmt, getString(TransitionConfig.nameRes(kind))), null, iconOf(kind), () -> {
				Element e = cfg.add(kind);
				e.y = 0.5f;
				if (kind == TransitionConfig.T_TEXT) e.text = getString(R.string.tr_sample_text);
				if (kind == TransitionConfig.T_PICTURE) e.visible = true;
				cfg.save(car);
				if (preview != null) {
					preview.refresh();
					preview.setSelected(e.id);
				}
				selectedId = e.id;
				openPanel(P_ELEMENT);
			});
		}
	}

	private void buildGeneral(LinearLayout b) {
		List<Runnable> s = sync;
		LinearLayout top = b;
		b = group(top, R.string.tr_section_when, true);
		switchRow(b, getString(R.string.tr_on), getString(R.string.tr_on_sub), () -> cfg.on, on -> {
			cfg.on = on;
			changed();
		}, s);
		flowRow(b, getString(R.string.tr_where), null, strings(R.string.tr_where_all, R.string.tr_where_full,
				R.string.tr_where_outside), () -> cfg.where, i -> {
			cfg.where = i;
			changed();
		}, s);
		sliderRow(b, getString(R.string.tr_hold), 10, 150, () -> cfg.holdTenths, v -> {
			cfg.holdTenths = v;
			changed();
		}, v -> getString(R.string.tr_seconds, v / 10f), s);
		switchRow(b, getString(R.string.tr_tap), getString(R.string.tr_tap_sub), () -> cfg.tapToDismiss, on -> {
			cfg.tapToDismiss = on;
			changed();
		}, s);
		switchRow(b, getString(R.string.tr_first), getString(R.string.tr_first_sub), () -> cfg.firstSong, on -> {
			cfg.firstSong = on;
			changed();
		}, s);

		b = group(top, R.string.tr_section_bg, wide);
		tilesRow(b, getString(R.string.tr_bg), strings(R.string.tr_bg_clear, R.string.tr_bg_dim, R.string.tr_bg_cover,
				R.string.tr_bg_picture, R.string.tr_bg_glass), i -> TransitionDemos.background(palette, i),
				() -> cfg.bg, i -> {
					cfg.bg = i;
					if (preview != null) {
						preview.setPicture(i == TransitionConfig.BG_PICTURE ? TransitionController.loadPicture(car) : null);
					}
					changed();
				}, s);
		sliderRow(b, getString(R.string.tr_dim), 0, 100, () -> cfg.dim, v -> {
			cfg.dim = v;
			changed();
		}, v -> v + "%", s);
		sliderRow(b, getString(R.string.tr_blur), 0, 100, () -> cfg.blur, v -> {
			cfg.blur = v;
			changed();
		}, v -> v + "%", s);
		sliderRow(b, getString(R.string.tr_zoom), 100, 300, () -> cfg.zoom, v -> {
			cfg.zoom = v;
			changed();
		}, v -> v + "%", s);
		actionRow(b, getString(R.string.tr_pick), getString(R.string.tr_pick_sub), R.drawable.home_image, this::pickPicture);
		actionRow(b, getString(R.string.tr_unpick), null, R.drawable.delete, () -> {
			TransitionController.pictureFile(car).delete();
			if (preview != null) preview.setPicture(null);
			TopToast.show(R.drawable.transition, R.string.tr_unpicked);
		});

		b = group(top, R.string.tr_section_anim, wide);
		tilesRow(b, getString(R.string.tr_screen_in), animNames(), i -> TransitionDemos.anim(palette, i, true, accent),
				() -> cfg.inAnim, i -> {
					cfg.inAnim = i;
					changed();
				}, s);
		sliderRow(b, getString(R.string.tr_screen_in_ms), 0, 2000, () -> cfg.inMs, v -> {
			cfg.inMs = v;
			changed();
		}, v -> v + " ms", s);
		tilesRow(b, getString(R.string.tr_screen_out), animNames(), i -> TransitionDemos.anim(palette, i, false, accent),
				() -> cfg.outAnim, i -> {
					cfg.outAnim = i;
					changed();
				}, s);
		sliderRow(b, getString(R.string.tr_screen_out_ms), 0, 2000, () -> cfg.outMs, v -> {
			cfg.outMs = v;
			changed();
		}, v -> v + " ms", s);

		actionRow(top, getString(R.string.tr_reset), getString(R.string.tr_reset_sub), R.drawable.refresh, () ->
				UiUtils.showQuestion(requireContext(), R.string.tr_reset, R.string.tr_reset_question, R.drawable.refresh)
						.onSuccess(ok -> onReset()));
	}

	private void buildElement(LinearLayout p, Element e) {
		List<Runnable> s = sync;
		LinearLayout top = p;
		p = group(top, R.string.tr_section_element, true);
		switchRow(p, getString(R.string.tr_show), null, () -> e.visible, on -> {
			e.visible = on;
			elementChanged();
		}, s);
		if (e.type == TransitionConfig.T_TEXT) {
			valueRow(p, getString(R.string.tr_text), () -> e.text, () -> UiUtils.queryText(requireContext(), R.string.tr_text,
					R.drawable.title, e.text).onSuccess(t -> {
				if (t == null) return;
				e.text = t;
				elementChanged();
			}), s);
		}
		if (e.type == TransitionConfig.T_CLOCK) {
			flowRow(p, getString(R.string.tr_clock_show), null, strings(R.string.tr_clock_time, R.string.tr_clock_date,
					R.string.tr_clock_both), () -> e.opt, i -> {
				e.opt = i;
				elementChanged();
			}, s);
		}
		if (e.type == TransitionConfig.T_PICTURE) {
			actionRow(p, getString(R.string.tr_pick_image), getString(R.string.tr_pick_image_sub), R.drawable.home_image,
					() -> pickElementPicture(e));
		}
		if (e.type == TransitionConfig.T_INFO) infoItems(p, e, s);
		if (e.isText()) {
			sliderRow(p, getString(R.string.tr_size), 15, 250, () -> Math.round(e.size * 1000), v -> {
				e.size = v / 1000f;
				elementChanged();
			}, v -> String.format(java.util.Locale.getDefault(), "%.1f%%", v / 10f), s);
		}
		sliderRow(p, getString(e.isText() ? R.string.tr_width : R.string.tr_cover_size), 6, e.isText() ? 100 : 300, () -> Math.round(e.w * 100), v -> {
			e.w = v / 100f;
			elementChanged();
		}, v -> v + "%", s);
		if (e.isText()) {
			sliderRow(p, getString(R.string.tr_height), 0, 80, () -> Math.round(e.h * 100), v -> {
				e.h = (v < 3) ? 0f : v / 100f;
				elementChanged();
			}, v -> (v < 3) ? getString(R.string.tr_auto) : v + "%", s);
		}
		if (e.type == TransitionConfig.T_SHAPE) {
			sliderRow(p, getString(R.string.tr_height), 1, 400, () -> Math.round(e.h * 1000), v -> {
				e.h = v / 1000f;
				elementChanged();
			}, v -> String.format(java.util.Locale.getDefault(), "%.1f%%", v / 10f), s);
		}
		sliderRow(p, getString(R.string.tr_pos_x), -50, 150, () -> Math.round(e.x * 100), v -> {
			e.x = v / 100f;
			elementChanged();
		}, v -> v + "%", s);
		sliderRow(p, getString(R.string.tr_pos_y), -50, 150, () -> Math.round(e.y * 100), v -> {
			e.y = v / 100f;
			elementChanged();
		}, v -> v + "%", s);

		if (e.isText()) {
			p = group(top, R.string.tr_section_text, wide);
			switchRow(p, getString(R.string.tr_wrap), getString(R.string.tr_wrap_sub), () -> e.wrap, on -> {
				e.wrap = on;
				elementChanged();
			}, s);
			sliderRow(p, getString(R.string.tr_lines), 1, 8, () -> e.lines, v -> {
				e.lines = v;
				elementChanged();
			}, v -> String.valueOf(v), s);
			switchRow(p, getString(R.string.tr_ellipsis), getString(R.string.tr_ellipsis_sub), () -> e.ellipsis, on -> {
				e.ellipsis = on;
				elementChanged();
			}, s);
			switchRow(p, getString(R.string.tr_fit), getString(R.string.tr_fit_sub), () -> e.autoFit, on -> {
				e.autoFit = on;
				elementChanged();
			}, s);
			tilesRow(p, getString(R.string.tr_font), fontNames(), i -> TransitionDemos.font(palette, i, textPrimary),
					() -> e.font, i -> {
						e.font = i;
						elementChanged();
					}, s);
			switchRow(p, getString(R.string.tr_caps), null, () -> e.caps, on -> {
				e.caps = on;
				elementChanged();
			}, s);
			sliderRow(p, getString(R.string.tr_spacing), -5, 40, () -> e.spacing, v -> {
				e.spacing = v;
				elementChanged();
			}, v -> String.valueOf(v), s);
			switchRow(p, getString(R.string.tr_bold), null, () -> e.bold, on -> {
				e.bold = on;
				elementChanged();
			}, s);
			switchRow(p, getString(R.string.tr_italic), null, () -> e.italic, on -> {
				e.italic = on;
				elementChanged();
			}, s);
			tilesRow(p, getString(R.string.tr_align), strings(R.string.tr_align_left, R.string.tr_align_center,
					R.string.tr_align_right), i -> TransitionDemos.align(palette, i, textPrimary), () -> e.align, i -> {
				e.align = i;
				elementChanged();
			}, s);
			swatchRow(p, getString(R.string.tr_color), () -> e.color, c -> {
				e.color = c;
				elementChanged();
			}, s);
		}

		p = group(top, R.string.tr_section_look, wide);
		tilesRow(p, getString(R.string.tr_fill), strings(R.string.tr_fill_none, R.string.tr_fill_solid, R.string.tr_fill_glass),
				i -> TransitionDemos.fill(palette, i, accent), () -> e.fill, i -> {
					e.fill = i;
					elementChanged();
				}, s);
		swatchRow(p, getString(R.string.tr_fill_color), () -> e.fillColor, c -> {
			e.fillColor = c;
			elementChanged();
		}, s);
		sliderRow(p, getString(R.string.tr_fill_alpha), 0, 100, () -> e.fillAlpha, v -> {
			e.fillAlpha = v;
			elementChanged();
		}, v -> v + "%", s);
		sliderRow(p, getString(e.isText() ? R.string.tr_corner : R.string.tr_corner_art), 0, 50, () -> e.radius, v -> {
			e.radius = v;
			elementChanged();
		}, v -> e.isText() ? String.valueOf(v) : (v >= 50 ? getString(R.string.tr_round) : v + "%"), s);
		sliderRow(p, getString(R.string.tr_pad), 0, 48, () -> e.pad, v -> {
			e.pad = v;
			elementChanged();
		}, v -> String.valueOf(v), s);
		sliderRow(p, getString(R.string.tr_edge), 0, 8, () -> e.edge, v -> {
			e.edge = v;
			elementChanged();
		}, v -> (v == 0) ? getString(R.string.tr_none) : String.valueOf(v), s);
		swatchRow(p, getString(R.string.tr_edge_color), () -> e.edgeColor, c -> {
			e.edgeColor = (c & 0x00FFFFFF) | 0xCC000000;
			elementChanged();
		}, s);

		p = group(top, R.string.tr_section_motion, wide);
		tilesRow(p, getString(R.string.tr_in), animNames(), i -> TransitionDemos.anim(palette, i, true, accent),
				() -> e.inAnim, i -> {
					e.inAnim = i;
					elementChanged();
					playPreview();
				}, s);
		sliderRow(p, getString(R.string.tr_in_ms), 0, 3000, () -> e.inMs, v -> {
			e.inMs = v;
			elementChanged();
		}, v -> v + " ms", s);
		sliderRow(p, getString(R.string.tr_delay), 0, 3000, () -> e.delay, v -> {
			e.delay = v;
			elementChanged();
		}, v -> v + " ms", s);
		tilesRow(p, getString(R.string.tr_out), animNames(), i -> TransitionDemos.anim(palette, i, false, accent),
				() -> e.outAnim, i -> {
					e.outAnim = i;
					elementChanged();
				}, s);
		sliderRow(p, getString(R.string.tr_out_ms), 0, 3000, () -> e.outMs, v -> {
			e.outMs = v;
			elementChanged();
		}, v -> v + " ms", s);

		actionRow(top, getString(R.string.tr_remove), getString(R.string.tr_remove_sub), R.drawable.delete, () ->
				UiUtils.showQuestion(requireContext(), R.string.tr_remove, R.string.tr_remove_question, R.drawable.delete)
						.onSuccess(ok -> removeSelected()));
	}

	private String[] fontNames() {
		String[] out = new String[me.aap.fermata.ui.transition.TransitionFonts.count()];
		int[] system = {R.string.tr_font_sans, R.string.tr_font_light, R.string.tr_font_condensed, R.string.tr_font_medium,
				R.string.tr_font_serif, R.string.tr_font_mono, R.string.tr_font_script, R.string.tr_font_casual};
		for (int i = 0; i < out.length; i++) {
			out[i] = getString((i < system.length) ? system[i] : me.aap.fermata.ui.transition.TransitionFonts.nameOf(i));
		}
		return out;
	}

	private String[] animNames() {
		return strings(R.string.tr_anim_none, R.string.tr_anim_fade, R.string.tr_anim_rise, R.string.tr_anim_drop,
				R.string.tr_anim_from_right, R.string.tr_anim_from_left, R.string.tr_anim_zoom_in, R.string.tr_anim_zoom_out);
	}

	/** Tiles with a small picture each (animations that play, backgrounds, fonts), the picked one outlined in the accent. */
	private void tilesRow(LinearLayout parent, String title, String[] labels, IntFunction<View> demo, IntSupplier get,
												IntConsumer set, List<Runnable> sync) {
		LinearLayout row = tileRow(parent, false);
		row.addView(label(title, 15, textPrimary, true));
		ChipFlowLayout flow = new ChipFlowLayout(palette);
		LinearLayout[] tiles = new LinearLayout[labels.length];
		for (int i = 0; i < labels.length; i++) {
			int fi = i;
			LinearLayout tile = new LinearLayout(palette);
			tile.setOrientation(LinearLayout.VERTICAL);
			tile.setGravity(Gravity.CENTER_HORIZONTAL);
			tile.setPadding(dp(5), dp(5), dp(5), dp(5));
			GradientDrawable fill = rounded(0x1F808080, 14);
			tile.setTag(R.id.settings_row_tag, fill);
			tile.setBackground(pressable(fill));
			tile.setClickable(true);
			tile.setFocusable(true);
			tile.setOnClickListener(v -> set.accept(fi));
			View d = demo.apply(i);
			tile.addView(d, new LinearLayout.LayoutParams(dp(compact() ? 62 : 76), dp(compact() ? 42 : 54)));
			TextView t = label(labels[i], 11.5f, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setEllipsize(android.text.TextUtils.TruncateAt.END);
			t.setPadding(0, dp(4), 0, 0);
			tile.addView(t, new LinearLayout.LayoutParams(dp(compact() ? 62 : 76), WRAP_CONTENT));
			tiles[i] = tile;
			flow.addView(tile, new android.view.ViewGroup.MarginLayoutParams(dp(compact() ? 74 : 88), WRAP_CONTENT));
		}
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		flp.topMargin = dp(8);
		row.addView(flow, flp);
		sync.add(() -> {
			int sel = get.getAsInt();
			for (int i = 0; i < tiles.length; i++) {
				boolean on = i == sel;
				GradientDrawable f = (GradientDrawable) tiles[i].getTag(R.id.settings_row_tag);
				fillColor(f, on ? androidx.core.graphics.ColorUtils.blendARGB(0x1F808080, accent, 0.22f) : 0x1F808080);
				f.setStroke(on ? dp(2) : 0, accent);
			}
		});
	}

	// ---- The info row's items ----

	/** The facts an info row shows: a switch for each, and arrows to move the shown ones up and down. */
	private void infoItems(LinearLayout parent, Element e, List<Runnable> s) {
		LinearLayout holder = new LinearLayout(palette);
		holder.setOrientation(LinearLayout.VERTICAL);
		parent.addView(holder, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		Runnable[] build = {null};
		build[0] = () -> {
			holder.removeAllViews();
			List<String> on = TransitionFacts.chosen(e.text);
			List<String> all = new ArrayList<>(on);
			for (String k : TransitionConfig.INFO_KEYS) if (!all.contains(k)) all.add(k);
			TextView head = label(getString(R.string.tr_info_items), 15, textPrimary, true);
			head.setPadding(dp(4), dp(10), dp(4), dp(2));
			holder.addView(head);
			for (String k : all) {
				boolean shown = on.contains(k);
				LinearLayout row = tileRow(holder, false);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(Gravity.CENTER_VERTICAL);
				row.addView(label(getString(TransitionFacts.nameRes(k)), 15, shown ? textPrimary : textSecondary, true),
						new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
				int idx = on.indexOf(k);
				if (shown) {
					TextView up = pill("\u25B2", 0, false, (idx > 0) ? () -> moveInfo(e, on, idx, idx - 1, build[0]) : null);
					TextView down = pill("\u25BC", 0, false, (idx < on.size() - 1) ? () -> moveInfo(e, on, idx, idx + 1, build[0]) : null);
					up.setAlpha(idx > 0 ? 1f : 0.35f);
					down.setAlpha(idx < on.size() - 1 ? 1f : 0.35f);
					LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(40), dp(36));
					plp.setMarginEnd(dp(6));
					row.addView(up, plp);
					row.addView(down, new LinearLayout.LayoutParams(dp(40), dp(36)));
				}
				android.widget.Switch sw = new android.widget.Switch(palette);
				sw.setChecked(shown);
				sw.setOnCheckedChangeListener((b, checked) -> {
					List<String> now = new ArrayList<>(on);
					if (checked) now.add(k);
					else now.remove(k);
					e.text = String.join(",", now);
					elementChanged();
					build[0].run();
				});
				LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
				slp.setMarginStart(dp(10));
				row.addView(sw, slp);
			}
		};
		build[0].run();
	}

	private void moveInfo(Element e, List<String> on, int from, int to, Runnable rebuild) {
		List<String> now = new ArrayList<>(on);
		String k = now.remove(from);
		now.add(to, k);
		e.text = String.join(",", now);
		elementChanged();
		rebuild.run();
	}

	// ---- Picking a picture for an element ----

	private void pickElementPicture(Element e) {
		MainActivityDelegate a = delegate();
		a.startActivityForResult(() -> new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
				.setType("image/*")).onCompletion((d, err) -> {
			Context ctx = a.getContext();
			if (err != null) {
				UiUtils.showAlert(ctx, getString(R.string.tr_pick_failed, err));
				return;
			}
			if ((d == null) || (d.getData() == null)) return;
			Uri uri = d.getData();
			App.get().getExecutor().submitTask(() -> TransitionController.saveElementPicture(ctx, uri)).main()
					.onCompletion((path, e2) -> {
						if ((e2 != null) || (path == null)) {
							Log.e(e2, "Failed to use the transition picture");
							UiUtils.showAlert(ctx, getString(R.string.tr_pick_failed, e2));
							return;
						}
						String old = e.text;
						e.text = path;
						e.visible = true;
						if ((old != null) && !old.isEmpty()) new java.io.File(old).delete();
						elementChanged();
						TopToast.show(R.drawable.transition, R.string.tr_picked);
					});
		});
	}

	// ---- Picking a picture ----

	private void pickPicture() {
		MainActivityDelegate a = delegate();
		boolean forCar = car;
		a.startActivityForResult(() -> new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
				.setType("image/*")).onCompletion((d, err) -> {
			Context ctx = a.getContext();
			if (err != null) {
				UiUtils.showAlert(ctx, getString(R.string.tr_pick_failed, err));
				return;
			}
			if ((d == null) || (d.getData() == null)) return;
			Uri uri = d.getData();
			App.get().getExecutor().submitTask(() -> {
				TransitionController.savePicture(ctx, uri, forCar);
				return null;
			}).main().onCompletion((r, e2) -> {
				if (e2 != null) {
					Log.e(e2, "Failed to use the transition picture");
					UiUtils.showAlert(ctx, getString(R.string.tr_pick_failed, e2));
					return;
				}
				if ((forCar == car) && (cfg != null)) {
					cfg.bg = TransitionConfig.BG_PICTURE;
					cfg.save(car);
					if (preview != null) {
						preview.setPicture(TransitionController.loadPicture(car));
						preview.refresh();
					}
					runSync();
				}
				TopToast.show(R.drawable.transition, R.string.tr_picked);
			});
		});
	}

	// ---- Row building ----

	private LinearLayout tileRow(LinearLayout parent, boolean clickable) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.VERTICAL);
		row.setPadding(dp(compact() ? 12 : 16), dp(compact() ? 8 : 12), dp(compact() ? 12 : 16), dp(compact() ? 8 : 12));
		row.setBackground(clickable ? pressable(rounded(tileFill, 16)) : rounded(tileFill, 16));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(compact() ? 6 : 10);
		parent.addView(row, lp);
		return row;
	}

	private TextView label(String s, float sp, int color, boolean bold) {
		TextView t = text(0, sp, color, bold);
		t.setText(s);
		return t;
	}

	private void switchRow(LinearLayout parent, String title, @Nullable String sub, BooleanSupplier get,
												 Consumer<Boolean> set, List<Runnable> sync) {
		LinearLayout row = tileRow(parent, true);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setClickable(true);
		row.setFocusable(true);
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(label(title, 15, textPrimary, true));
		if (sub != null) labels.addView(label(sub, 12.5f, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(palette);
		SwitchStyle.apply(sw, palette);
		row.addView(sw);
		boolean[] syncing = {false};
		sw.setOnCheckedChangeListener((b, on) -> {
			if (!syncing[0]) set.accept(on);
		});
		row.setOnClickListener(v -> sw.toggle());
		sync.add(() -> {
			syncing[0] = true;
			sw.setChecked(get.getAsBoolean());
			syncing[0] = false;
		});
	}

	private void sliderRow(LinearLayout parent, String title, int min, int max, IntSupplier get, IntConsumer set,
												 IntFunction<String> format, List<Runnable> sync) {
		LinearLayout row = tileRow(parent, false);
		row.setPadding(dp(compact() ? 12 : 16), dp(compact() ? 6 : 10), dp(compact() ? 12 : 16), dp(2));
		LinearLayout top = new LinearLayout(palette);
		top.addView(label(title, 15, textPrimary, true), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		TextView value = label("", 14, textSecondary, true);
		top.addView(value);
		row.addView(top);
		SeekBar bar = newSeekBar();
		bar.setMax(max - min);
		boolean[] syncing = {false};
		bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
				value.setText(format.apply(min + progress));
				if (fromUser && !syncing[0]) set.accept(min + progress);
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});
		row.addView(bar, new LinearLayout.LayoutParams(MATCH_PARENT, dp(compact() ? 30 : 36)));
		sync.add(() -> {
			int progress = Math.max(0, Math.min(max - min, get.getAsInt() - min));
			syncing[0] = true;
			if (bar.getProgress() != progress) bar.setProgress(progress);
			value.setText(format.apply(min + progress));
			syncing[0] = false;
		});
	}

	/** Pills that wrap onto more lines, the picked one filled with the accent. */
	private void flowRow(LinearLayout parent, String title, @Nullable String sub, String[] labels, IntSupplier get,
											 IntConsumer set, List<Runnable> sync) {
		LinearLayout row = tileRow(parent, false);
		row.addView(label(title, 15, textPrimary, true));
		if (sub != null) row.addView(label(sub, 12.5f, textSecondary, false));
		ChipFlowLayout flow = new ChipFlowLayout(palette);
		TextView[] views = new TextView[labels.length];
		for (int i = 0; i < labels.length; i++) {
			int fi = i;
			TextView t = label(labels[i], 13.5f, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setPadding(dp(compact() ? 11 : 14), dp(compact() ? 7 : 9), dp(compact() ? 11 : 14), dp(compact() ? 7 : 9));
			GradientDrawable fill = rounded(0x1F808080, 14);
			t.setTag(R.id.settings_row_tag, fill);
			t.setBackground(pressable(fill));
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> set.accept(fi));
			views[i] = t;
			flow.addView(t);
		}
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		flp.topMargin = dp(8);
		row.addView(flow, flp);
		sync.add(() -> {
			int sel = get.getAsInt();
			for (int i = 0; i < views.length; i++) {
				boolean on = i == sel;
				views[i].setTextColor(on ? onAccent : textPrimary);
				fillColor((GradientDrawable) views[i].getTag(R.id.settings_row_tag), on ? accent : 0x1F808080);
			}
		});
	}

	private void swatchRow(LinearLayout parent, String title, IntSupplier get, IntConsumer set, List<Runnable> sync) {
		LinearLayout row = tileRow(parent, false);
		row.addView(label(title, 15, textPrimary, true));
		ChipFlowLayout flow = new ChipFlowLayout(palette);
		View[] views = new View[SWATCHES.length];
		for (int i = 0; i < SWATCHES.length; i++) {
			int c = SWATCHES[i];
			View v = new View(palette);
			GradientDrawable d = new GradientDrawable();
			d.setShape(GradientDrawable.OVAL);
			d.setColor(c);
			v.setBackground(d);
			v.setTag(R.id.settings_row_tag, d);
			v.setClickable(true);
			v.setFocusable(true);
			v.setOnClickListener(x -> set.accept(c));
			views[i] = v;
			flow.addView(v, new android.view.ViewGroup.MarginLayoutParams(dp(compact() ? 30 : 34), dp(compact() ? 30 : 34)));
		}
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		flp.topMargin = dp(8);
		row.addView(flow, flp);
		sync.add(() -> {
			int cur = get.getAsInt() | 0xFF000000;
			for (int i = 0; i < views.length; i++) {
				GradientDrawable d = (GradientDrawable) views[i].getTag(R.id.settings_row_tag);
				boolean on = (SWATCHES[i] | 0xFF000000) == cur;
				d.setStroke(on ? dp(3) : dp(1), on ? accent : 0x66808080);
			}
		});
	}

	private void valueRow(LinearLayout parent, String title, java.util.function.Supplier<String> get, Runnable click,
												List<Runnable> sync) {
		LinearLayout row = tileRow(parent, true);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setClickable(true);
		row.setFocusable(true);
		row.addView(label(title, 15, textPrimary, true), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		TextView value = label("", 14, accent, true);
		value.setSingleLine(true);
		value.setEllipsize(android.text.TextUtils.TruncateAt.END);
		value.setMaxWidth(dp(200));
		row.addView(value);
		row.setOnClickListener(v -> click.run());
		sync.add(() -> value.setText(get.get()));
	}

	private void actionRow(LinearLayout parent, String title, @Nullable String sub, @DrawableRes int icon, Runnable click) {
		LinearLayout row = tileRow(parent, true);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setClickable(true);
		row.setFocusable(true);
		android.widget.ImageView iv = new android.widget.ImageView(palette);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(accent));
		row.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.setPadding(dp(14), 0, dp(8), 0);
		labels.addView(label(title, 15, textPrimary, true));
		if (sub != null) labels.addView(label(sub, 12.5f, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		row.setOnClickListener(v -> click.run());
	}

	private TextView pill(String text, @DrawableRes int icon, boolean primary, @Nullable Runnable click) {
		TextView t = label(text, 13.5f, primary ? onAccent : textSecondary, primary);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setPadding(dp(12), 0, dp(12), 0);
		GradientDrawable fill = rounded(primary ? accent : 0x1F808080, 20);
		t.setBackground(click != null ? pressable(fill) : fill);
		if (icon != 0) {
			t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
			t.setCompoundDrawablePadding(dp(6));
			t.setCompoundDrawableTintList(ColorStateList.valueOf(primary ? onAccent : textSecondary));
		}
		if (click != null) {
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> click.run());
		}
		return t;
	}
}
