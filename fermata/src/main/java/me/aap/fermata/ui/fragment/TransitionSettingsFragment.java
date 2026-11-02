package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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

	/** Keeps the shape of the screen shown, as large as the room allows, between the chips and the hint. */
	private final class Stage extends FrameLayout {
		private float aspect = 16f / 9f;

		Stage(Context c) {
			super(c);
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
			int fw = Math.min(w - dp(24), Math.round((h - dp(64 + 34)) * aspect));
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
				int room = h - dp(64 + 34);
				int top = dp(64) + Math.max(0, (room - ch) / 2);
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
		wide = (ctx.getResources().getConfiguration().screenWidthDp >= 720) || ((d != null) && d.isCarActivity());

		root = new FrameLayout(palette);
		LinearLayout row = new LinearLayout(palette);
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

		TextView hint = text(R.string.tr_edit_hint, 12.5f, textSecondary, false);
		hint.setGravity(Gravity.CENTER);
		FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM);
		hlp.bottomMargin = dp(8);
		hlp.setMarginEnd(wide ? dp(380) : 0);
		root.addView(hint, hlp);

		if (wide) {
			sidePanel = new FrameLayout(palette);
			LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(380), MATCH_PARENT);
			slp.setMargins(0, dp(4), dp(8), dp(8));
			row.addView(sidePanel, slp);
		} else {
			sheet = new FrameLayout(palette);
			sheet.setVisibility(View.GONE);
			FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(MATCH_PARENT, dp(300), Gravity.BOTTOM);
			slp.setMargins(dp(8), 0, dp(8), dp(8));
			root.addView(sheet, slp);
			root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
				if (sheet == null) return;
				int h = Math.round((b - t) * 0.58f);
				android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
				if (lp.height != h) {
					lp.height = h;
					sheet.setLayoutParams(lp);
				}
			});
		}

		// The set, and the preview's own chips: settings, add, play.
		LinearLayout left = new LinearLayout(palette);
		left.addView(ChipFlowLayout.chip(palette, R.drawable.home_page, getString(R.string.home_settings_tab_phone), !car,
				() -> switchSet(false)));
		LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		gap.setMarginStart(dp(6));
		left.addView(ChipFlowLayout.chip(palette, R.drawable.car, getString(R.string.home_settings_tab_aa), car,
				() -> switchSet(true)), gap);
		FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP | Gravity.START);
		llp.setMargins(dp(8), dp(8), 0, 0);
		root.addView(left, llp);
		setChips = left;

		LinearLayout right = new LinearLayout(palette);
		right.addView(ChipFlowLayout.chip(palette, R.drawable.play, getString(R.string.tr_play), true, this::playPreview));
		LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		g2.setMarginStart(dp(6));
		right.addView(ChipFlowLayout.chip(palette, R.drawable.home_add, "", false, () -> openPanel(P_ADD)), g2);
		right.addView(ChipFlowLayout.chip(palette, R.drawable.settings, "", false, () -> {
			selectedId = 0;
			if (preview != null) preview.setSelected(0);
			openPanel(P_GENERAL);
		}), g2);
		FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP | Gravity.END);
		rlp.setMargins(0, dp(8), wide ? dp(388) : dp(8), 0);
		root.addView(right, rlp);

		// What plays now is on the preview, with its cover (nothing playing: the samples).
		if (d != null) {
			me.aap.fermata.media.lib.MediaLib.PlayableItem pi = me.aap.fermata.ui.home.HomeArt.displayItem(d);
			if (pi != null) TransitionController.loadInto(d, pi, preview);
		}

		if (wide) openPanel(P_GENERAL);
		return root;
	}

	@Nullable
	private LinearLayout setChips;

	private void updateAspect() {
		Stage st = stage;
		if (st == null) return;
		android.util.DisplayMetrics dm = palette.getResources().getDisplayMetrics();
		float screen = (float) Math.max(dm.widthPixels, dm.heightPixels) / Math.min(dm.widthPixels, dm.heightPixels);
		// The car screen is wide; the phone's own is taken as it is held now.
		st.setAspect(car ? 16f / 9f : (dm.widthPixels < dm.heightPixels ? 1f / screen : screen));
	}

	private void playPreview() {
		TransitionView v = preview;
		if ((v == null) || playing) return;
		playing = true;
		if (cfg.bg == TransitionConfig.BG_PICTURE) v.setPicture(TransitionController.loadPicture(car));
		v.playIn();
		long stay = Math.min(2500, cfg.holdTenths * 100L) + cfg.inMs;
		v.postDelayed(() -> v.playOut(() -> {
			v.showNow();
			playing = false;
		}), stay);
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
		LinearLayout sc = setChips;
		if ((sc != null) && (sc.getChildCount() == 2)) {
			// Light the picked one.
			sc.removeAllViews();
			sc.addView(ChipFlowLayout.chip(palette, R.drawable.home_page, getString(R.string.home_settings_tab_phone), !car,
					() -> switchSet(false)));
			LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			gap.setMarginStart(dp(6));
			sc.addView(ChipFlowLayout.chip(palette, R.drawable.car, getString(R.string.home_settings_tab_aa), car,
					() -> switchSet(true)), gap);
		}
		updateAspect();
		closeSheet();
		if (wide) openPanel(P_GENERAL);
	}

	@Override
	public boolean onBackPressed() {
		if (sheetOpen) {
			closeSheet();
			return true;
		}
		return super.onBackPressed();
	}

	@Override
	public void onDestroyView() {
		cfg = null;
		preview = null;
		root = null;
		stage = null;
		sidePanel = null;
		sheet = null;
		panelCard = null;
		setChips = null;
		sync.clear();
		sheetOpen = false;
		super.onDestroyView();
	}

	// ---- The panel ----

	/** Shows the panel for {@code mode}: in the side column (wide), or as a sheet over the preview (phone). */
	private void openPanel(int mode) {
		panelMode = mode;
		LinearLayout card = buildPanel();
		panelCard = card;
		if (wide) {
			FrameLayout host = sidePanel;
			if (host == null) return;
			host.removeAllViews();
			host.addView(card, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			card.setAlpha(0f);
			card.setTranslationX(dp(28));
			card.animate().alpha(1f).translationX(0f).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
		} else {
			FrameLayout host = sheet;
			if (host == null) return;
			boolean wasOpen = sheetOpen;
			host.removeAllViews();
			host.addView(card, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			host.setVisibility(View.VISIBLE);
			sheetOpen = true;
			if (!wasOpen) {
				host.setTranslationY(dp(220));
				host.setAlpha(0f);
				host.animate().translationY(0f).alpha(1f).setDuration(240)
						.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
			} else {
				card.setAlpha(0f);
				card.animate().alpha(1f).setDuration(150).start();
			}
		}
	}

	private void closeSheet() {
		FrameLayout host = sheet;
		if ((host == null) || !sheetOpen) return;
		sheetOpen = false;
		host.animate().translationY(dp(220)).alpha(0f).setDuration(200)
				.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f))
				.withEndAction(() -> {
					host.setVisibility(View.GONE);
					host.removeAllViews();
				}).start();
		selectedId = 0;
		if (preview != null) preview.setSelected(0);
	}

	/** A card: the chips that pick what to edit, a title, and the options. */
	private LinearLayout buildPanel() {
		sync.clear();
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClickable(true);
		card.setElevation(dp(8));
		card.setBackground(me.aap.fermata.ui.view.MenuStyle.panel(card, paletteColor(R.attr.musicPanelFill), dp(24)));
		card.setPadding(dp(10), dp(10), dp(10), dp(8));

		// What to edit: the screen's own settings, each element, add. (Also how a steering wheel picks one.)
		LinearLayout head = new LinearLayout(palette);
		head.setGravity(Gravity.CENTER_VERTICAL);
		android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(palette);
		hs.setHorizontalScrollBarEnabled(false);
		ChipFlowLayout chips = new ChipFlowLayout(palette);
		chips.setOneRow(true);
		chips.addView(ChipFlowLayout.chip(palette, R.drawable.settings, getString(R.string.tr_general),
				panelMode == P_GENERAL, () -> {
			selectedId = 0;
			if (preview != null) preview.setSelected(0);
			openPanel(P_GENERAL);
		}));
		for (Element e : cfg.elements) {
			TextView c = ChipFlowLayout.chip(palette, iconOf(e.type), nameOf(e), (panelMode == P_ELEMENT) && (e.id == selectedId),
					() -> pick(e.id));
			c.setAlpha(e.visible ? 1f : 0.5f);
			chips.addView(c);
		}
		chips.addView(ChipFlowLayout.chip(palette, R.drawable.home_add, "", panelMode == P_ADD, () -> openPanel(P_ADD)));
		hs.addView(chips);
		head.addView(hs, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
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
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(38), dp(38));
		clp.setMarginStart(dp(6));
		head.addView(close, clp);
		card.addView(head, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		android.widget.ScrollView scroll = new android.widget.ScrollView(palette);
		scroll.setVerticalScrollBarEnabled(false);
		LinearLayout body = new LinearLayout(palette);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPadding(0, 0, 0, dp(12));
		scroll.addView(body, new android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		card.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		switch (panelMode) {
			case P_ELEMENT -> {
				if (selected() == null) buildGeneral(body);
				else buildElement(body, selected());
			}
			case P_ADD -> buildAdd(body);
			default -> buildGeneral(body);
		}
		runSync();
		me.aap.fermata.action.CarNav.markScope(card, wide ? null : this::closeSheet);
		return card;
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
		pick(id);
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

	private void buildAdd(LinearLayout p) {
		section(p, R.string.tr_section_elements);
		TextView hint = text(R.string.tr_elements_hint, 12.5f, textSecondary, false);
		hint.setPadding(dp(4), 0, dp(4), dp(6));
		p.addView(hint);
		int[] kinds = {TransitionConfig.T_TITLE, TransitionConfig.T_CHANNEL, TransitionConfig.T_ART,
				TransitionConfig.T_PLAYLIST, TransitionConfig.T_TEXT};
		for (int kind : kinds) {
			actionRow(p, getString(R.string.tr_add_fmt, getString(TransitionConfig.nameRes(kind))), null, iconOf(kind), () -> {
				Element e = cfg.add(kind);
				e.y = 0.5f;
				if (kind == TransitionConfig.T_TEXT) e.text = getString(R.string.tr_sample_text);
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
		section(b, R.string.tr_section_when);
		switchRow(b, getString(R.string.tr_on), getString(R.string.tr_on_sub), () -> cfg.on, on -> {
			cfg.on = on;
			changed();
		}, s);
		flowRow(b, getString(R.string.tr_where), null, strings(R.string.tr_where_all, R.string.tr_where_full,
				R.string.tr_where_outside), () -> cfg.where, i -> {
			cfg.where = i;
			changed();
		}, s);
		sliderRow(b, getString(R.string.tr_hold), 10, 300, () -> cfg.holdTenths, v -> {
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

		section(b, R.string.tr_section_bg);
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
		actionRow(b, getString(R.string.tr_pick), getString(R.string.tr_pick_sub), R.drawable.home_image, this::pickPicture);
		actionRow(b, getString(R.string.tr_unpick), null, R.drawable.delete, () -> {
			TransitionController.pictureFile(car).delete();
			if (preview != null) preview.setPicture(null);
			TopToast.show(R.drawable.transition, R.string.tr_unpicked);
		});

		section(b, R.string.tr_section_anim);
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

		actionRow(b, getString(R.string.tr_reset), getString(R.string.tr_reset_sub), R.drawable.refresh, () ->
				UiUtils.showQuestion(requireContext(), R.string.tr_reset, R.string.tr_reset_question, R.drawable.refresh)
						.onSuccess(ok -> onReset()));
	}

	private void buildElement(LinearLayout p, Element e) {
		List<Runnable> s = sync;
		section(p, R.string.tr_section_element);
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
		if (e.isText()) {
			sliderRow(p, getString(R.string.tr_size), 15, 250, () -> Math.round(e.size * 1000), v -> {
				e.size = v / 1000f;
				elementChanged();
			}, v -> String.format(java.util.Locale.getDefault(), "%.1f%%", v / 10f), s);
		}
		sliderRow(p, getString(e.isText() ? R.string.tr_width : R.string.tr_cover_size), 6, 100, () -> Math.round(e.w * 100), v -> {
			e.w = v / 100f;
			elementChanged();
		}, v -> v + "%", s);
		sliderRow(p, getString(R.string.tr_pos_x), 0, 100, () -> Math.round(e.x * 100), v -> {
			e.x = v / 100f;
			elementChanged();
		}, v -> v + "%", s);
		sliderRow(p, getString(R.string.tr_pos_y), 0, 100, () -> Math.round(e.y * 100), v -> {
			e.y = v / 100f;
			elementChanged();
		}, v -> v + "%", s);

		if (e.isText()) {
			section(p, R.string.tr_section_text);
			tilesRow(p, getString(R.string.tr_font), strings(R.string.tr_font_sans, R.string.tr_font_light,
					R.string.tr_font_condensed, R.string.tr_font_medium, R.string.tr_font_serif, R.string.tr_font_mono,
					R.string.tr_font_script, R.string.tr_font_casual), i -> TransitionDemos.font(palette, i, textPrimary),
					() -> e.font, i -> {
						e.font = i;
						elementChanged();
					}, s);
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

		section(p, R.string.tr_section_look);
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
		}, v -> e.isText() ? v + " dp" : (v >= 50 ? getString(R.string.tr_round) : v + "%"), s);
		sliderRow(p, getString(R.string.tr_pad), 0, 48, () -> e.pad, v -> {
			e.pad = v;
			elementChanged();
		}, v -> v + " dp", s);
		sliderRow(p, getString(R.string.tr_edge), 0, 8, () -> e.edge, v -> {
			e.edge = v;
			elementChanged();
		}, v -> (v == 0) ? getString(R.string.tr_none) : v + " dp", s);
		swatchRow(p, getString(R.string.tr_edge_color), () -> e.edgeColor, c -> {
			e.edgeColor = (c & 0x00FFFFFF) | 0xCC000000;
			elementChanged();
		}, s);

		section(p, R.string.tr_section_motion);
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

		actionRow(p, getString(R.string.tr_remove), getString(R.string.tr_remove_sub), R.drawable.delete, () ->
				UiUtils.showQuestion(requireContext(), R.string.tr_remove, R.string.tr_remove_question, R.drawable.delete)
						.onSuccess(ok -> {
							cfg.remove(e);
							selectedId = 0;
							cfg.save(car);
							if (preview != null) {
								preview.setSelected(0);
								preview.refresh();
							}
							if (wide) openPanel(P_GENERAL);
							else closeSheet();
						}));
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
			tile.setPadding(dp(6), dp(6), dp(6), dp(6));
			GradientDrawable fill = rounded(0x1F808080, 14);
			tile.setTag(R.id.settings_row_tag, fill);
			tile.setBackground(pressable(fill));
			tile.setClickable(true);
			tile.setFocusable(true);
			tile.setOnClickListener(v -> set.accept(fi));
			View d = demo.apply(i);
			tile.addView(d, new LinearLayout.LayoutParams(dp(76), dp(54)));
			TextView t = label(labels[i], 11.5f, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setEllipsize(android.text.TextUtils.TruncateAt.END);
			t.setPadding(0, dp(4), 0, 0);
			tile.addView(t, new LinearLayout.LayoutParams(dp(76), WRAP_CONTENT));
			tiles[i] = tile;
			flow.addView(tile, new android.view.ViewGroup.MarginLayoutParams(dp(88), WRAP_CONTENT));
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
		row.setPadding(dp(16), dp(12), dp(16), dp(12));
		row.setBackground(clickable ? pressable(rounded(tileFill, 16)) : rounded(tileFill, 16));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
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
		row.setPadding(dp(16), dp(10), dp(16), dp(4));
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
		row.addView(bar, new LinearLayout.LayoutParams(MATCH_PARENT, dp(36)));
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
			t.setPadding(dp(14), dp(9), dp(14), dp(9));
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
			flow.addView(v, new android.view.ViewGroup.MarginLayoutParams(dp(34), dp(34)));
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
