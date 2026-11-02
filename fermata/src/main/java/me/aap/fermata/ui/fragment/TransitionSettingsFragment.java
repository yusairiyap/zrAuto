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
import android.widget.SwitchCompat;
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
	@Nullable
	private LinearLayout box;
	@Nullable
	private LinearLayout panel;
	@Nullable
	private ChipFlowLayout elementChips;
	@Nullable
	private TextView phoneTab;
	@Nullable
	private TextView carTab;
	/** Brings the rows of the set in line with it; the picked element's rows have their own list (they are made again). */
	private final List<Runnable> setSync = new ArrayList<>();
	private final List<Runnable> panelSync = new ArrayList<>();
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
		car = carInput;
		if (getView() != null) switchSet(car);
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
			preview.setPicture(null);
		}
		buildAll();
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

	// ---- The preview, always in view ----

	/** Keeps the shape of the screen shown: as wide as fits, no taller than {@code maxH}. */
	private final class PreviewFrame extends FrameLayout {
		private float aspect = 16f / 9f;

		PreviewFrame(Context c) {
			super(c);
		}

		void setAspect(float a) {
			aspect = a;
			requestLayout();
		}

		@Override
		protected void onMeasure(int wSpec, int hSpec) {
			int avail = MeasureSpec.getSize(wSpec);
			int maxH = dp(190);
			int w = Math.min(avail, Math.round(maxH * aspect));
			int h = Math.round(w / aspect);
			setMeasuredDimension(avail, h);
			for (int i = 0; i < getChildCount(); i++) {
				View c = getChildAt(i);
				c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
			}
		}

		@Override
		protected void onLayout(boolean changed, int l, int t, int r, int b) {
			for (int i = 0; i < getChildCount(); i++) {
				View c = getChildAt(i);
				int cw = c.getMeasuredWidth();
				int left = ((r - l) - cw) / 2;
				c.layout(left, 0, left + cw, c.getMeasuredHeight());
			}
		}
	}

	private PreviewFrame frame;

	/** The set being edited, loaded on first use (the page builds its options before its preview). */
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
	protected View createStickyTop() {
		ensureCfg();
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(10), dp(10), dp(10), dp(8));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(tileFill | 0xFF000000);
		bg.setCornerRadius(dp(20));
		bg.setStroke(dp(1), 0x33808080);
		card.setBackground(bg);

		frame = new PreviewFrame(palette);
		// A stand-in for the app behind it, so a clear or dim screen can be told from a solid one.
		GradientDrawable scene = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
				new int[]{0xFF2B5876, 0xFF4E4376});
		scene.setCornerRadius(dp(12));
		FrameLayout stage = new FrameLayout(palette);
		stage.setBackground(scene);
		stage.setClipToOutline(true);
		stage.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
		preview = new TransitionView(palette, cfg);
		preview.setSample(true);
		preview.setEditor(this);
		stage.addView(preview, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		frame.addView(stage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		card.addView(frame, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		updateAspect();

		LinearLayout buttons = new LinearLayout(palette);
		buttons.setGravity(Gravity.CENTER);
		buttons.addView(pill(getString(R.string.tr_play), R.drawable.play, true, this::playPreview),
				new LinearLayout.LayoutParams(0, dp(40), 1f));
		LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(40), 1f);
		second.setMarginStart(dp(8));
		buttons.addView(pill(getString(R.string.tr_edit_hint), 0, false, null), second);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = dp(8);
		card.addView(buttons, blp);
		return card;
	}

	private void updateAspect() {
		if (frame == null) return;
		android.util.DisplayMetrics dm = palette.getResources().getDisplayMetrics();
		float screen = (float) Math.max(dm.widthPixels, dm.heightPixels) / Math.min(dm.widthPixels, dm.heightPixels);
		// The car screen is wide; the phone's own is taken as it is held now.
		frame.setAspect(car ? 16f / 9f : (dm.widthPixels < dm.heightPixels ? 1f / screen : screen));
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

	// ---- The options ----

	@Override
	protected View createExtraHeader() {
		ensureCfg();
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);
		LinearLayout tabs = new LinearLayout(palette);
		phoneTab = tab(R.string.home_settings_tab_phone, R.drawable.home_page, false);
		carTab = tab(R.string.home_settings_tab_aa, R.drawable.car, true);
		LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		l1.setMarginEnd(dp(4));
		LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		l2.setMarginStart(dp(4));
		tabs.addView(phoneTab, l1);
		tabs.addView(carTab, l2);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		tlp.topMargin = dp(6);
		h.addView(tabs, tlp);
		box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		h.addView(box, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		lightTabs();
		buildAll();
		return h;
	}

	private TextView tab(@StringRes int label, @DrawableRes int icon, boolean forCar) {
		TextView t = text(label, 14, textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		t.setCompoundDrawablePadding(dp(8));
		t.setPadding(dp(12), dp(13), dp(12), dp(13));
		GradientDrawable fill = rounded(tileFill, 16);
		t.setTag(R.id.transition_settings_fragment, fill);
		t.setBackground(pressable(fill));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> switchSet(forCar));
		return t;
	}

	private void lightTabs() {
		light(phoneTab, !car);
		light(carTab, car);
	}

	private void light(@Nullable TextView t, boolean on) {
		if (t == null) return;
		GradientDrawable fill = (GradientDrawable) t.getTag(R.id.transition_settings_fragment);
		fillColor(fill, on ? accent : tileFill);
		t.setTextColor(on ? onAccent : textPrimary);
		t.setCompoundDrawableTintList(ColorStateList.valueOf(on ? onAccent : textSecondary));
	}

	private void switchSet(boolean forCar) {
		car = forCar;
		selectedId = 0;
		cfg = TransitionConfig.load(car);
		if (preview != null) {
			preview.setConfig(cfg);
			preview.setSelected(0);
			preview.setPicture(cfg.bg == TransitionConfig.BG_PICTURE ? TransitionController.loadPicture(car) : null);
		}
		updateAspect();
		lightTabs();
		buildAll();
	}

	@Override
	public void onDestroyView() {
		cfg = null;
		preview = null;
		box = null;
		panel = null;
		elementChips = null;
		phoneTab = null;
		carTab = null;
		frame = null;
		setSync.clear();
		panelSync.clear();
		super.onDestroyView();
	}

	/** Makes every row of the set again (the set changed, or was reset). */
	private void buildAll() {
		LinearLayout b = box;
		if (b == null) return;
		b.removeAllViews();
		setSync.clear();
		panelSync.clear();
		List<Runnable> s = setSync;

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
		flowRow(b, getString(R.string.tr_bg), null, strings(R.string.tr_bg_clear, R.string.tr_bg_dim, R.string.tr_bg_cover,
				R.string.tr_bg_picture, R.string.tr_bg_glass), () -> cfg.bg, i -> {
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
		flowRow(b, getString(R.string.tr_screen_in), null, animNames(), () -> cfg.inAnim, i -> {
			cfg.inAnim = i;
			changed();
		}, s);
		sliderRow(b, getString(R.string.tr_screen_in_ms), 0, 2000, () -> cfg.inMs, v -> {
			cfg.inMs = v;
			changed();
		}, v -> v + " ms", s);
		flowRow(b, getString(R.string.tr_screen_out), null, animNames(), () -> cfg.outAnim, i -> {
			cfg.outAnim = i;
			changed();
		}, s);
		sliderRow(b, getString(R.string.tr_screen_out_ms), 0, 2000, () -> cfg.outMs, v -> {
			cfg.outMs = v;
			changed();
		}, v -> v + " ms", s);

		section(b, R.string.tr_section_elements);
		TextView hint = text(R.string.tr_elements_hint, 12.5f, textSecondary, false);
		hint.setPadding(dp(4), 0, dp(4), dp(6));
		b.addView(hint);
		elementChips = new ChipFlowLayout(palette);
		b.addView(elementChips, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		ChipFlowLayout add = new ChipFlowLayout(palette);
		int[] kinds = {TransitionConfig.T_TITLE, TransitionConfig.T_CHANNEL, TransitionConfig.T_ART,
				TransitionConfig.T_PLAYLIST, TransitionConfig.T_TEXT};
		for (int kind : kinds) {
			add.addView(ChipFlowLayout.chip(palette, R.drawable.home_add, getString(R.string.tr_add_fmt,
					getString(TransitionConfig.nameRes(kind))), false, () -> {
				Element e = cfg.add(kind);
				e.y = 0.5f;
				if (kind == TransitionConfig.T_TEXT) e.text = getString(R.string.tr_sample_text);
				selectedId = e.id;
				cfg.save(car);
				if (preview != null) preview.refresh();
				pick(e.id);
			}));
		}
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		alp.topMargin = dp(6);
		b.addView(add, alp);

		panel = new LinearLayout(palette);
		panel.setOrientation(LinearLayout.VERTICAL);
		b.addView(panel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		refreshElementChips();
		buildPanel();
		runSync();
	}

	private String[] strings(@StringRes int... ids) {
		String[] out = new String[ids.length];
		for (int i = 0; i < ids.length; i++) out[i] = getString(ids[i]);
		return out;
	}

	private String[] animNames() {
		return strings(R.string.tr_anim_none, R.string.tr_anim_fade, R.string.tr_anim_rise, R.string.tr_anim_drop,
				R.string.tr_anim_from_right, R.string.tr_anim_from_left, R.string.tr_anim_zoom_in, R.string.tr_anim_zoom_out);
	}

	private void changed() {
		cfg.save(car);
		if (preview != null) preview.refresh();
		runSync();
	}

	private void runSync() {
		for (Runnable r : new ArrayList<>(setSync)) r.run();
		for (Runnable r : new ArrayList<>(panelSync)) r.run();
	}

	// ---- Editing in the preview ----

	@Override
	public void onPicked(int id) {
		pick(id);
	}

	@Override
	public void onEdited(Element e, boolean done) {
		for (Runnable r : new ArrayList<>(panelSync)) r.run();
		if (done) cfg.save(car);
	}

	private void pick(int id) {
		selectedId = id;
		if (preview != null) preview.setSelected(id);
		refreshElementChips();
		buildPanel();
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

	private void refreshElementChips() {
		ChipFlowLayout c = elementChips;
		if (c == null) return;
		c.removeAllViews();
		for (Element e : cfg.elements) {
			TextView t = ChipFlowLayout.chip(palette, iconOf(e.type), nameOf(e), e.id == selectedId, () -> pick(e.id));
			t.setAlpha(e.visible ? 1f : 0.5f);
			c.addView(t);
		}
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

	/** The rows of the picked element. */
	private void buildPanel() {
		LinearLayout p = panel;
		if (p == null) return;
		p.removeAllViews();
		panelSync.clear();
		Element e = selected();
		if (e == null) {
			TextView none = text(R.string.tr_pick_element, 13.5f, textSecondary, false);
			none.setPadding(dp(4), dp(14), dp(4), dp(4));
			p.addView(none);
			return;
		}
		List<Runnable> s = panelSync;
		section(p, R.string.tr_section_element);
		TextView name = text(0, 17, textPrimary, true);
		name.setText(nameOf(e));
		name.setPadding(dp(4), 0, 0, dp(2));
		p.addView(name);

		switchRow(p, getString(R.string.tr_show), null, () -> e.visible, on -> {
			e.visible = on;
			elementChanged();
			refreshElementChips();
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
			flowRow(p, getString(R.string.tr_font), null, strings(R.string.tr_font_sans, R.string.tr_font_light,
					R.string.tr_font_condensed, R.string.tr_font_medium, R.string.tr_font_serif, R.string.tr_font_mono,
					R.string.tr_font_script, R.string.tr_font_casual), () -> e.font, i -> {
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
			flowRow(p, getString(R.string.tr_align), null, strings(R.string.tr_align_left, R.string.tr_align_center,
					R.string.tr_align_right), () -> e.align, i -> {
				e.align = i;
				elementChanged();
			}, s);
			swatchRow(p, getString(R.string.tr_color), () -> e.color, c -> {
				e.color = c;
				elementChanged();
			}, s);
		}

		flowRow(p, getString(R.string.tr_fill), null, strings(R.string.tr_fill_none, R.string.tr_fill_solid,
				R.string.tr_fill_glass), () -> e.fill, i -> {
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

		flowRow(p, getString(R.string.tr_in), null, animNames(), () -> e.inAnim, i -> {
			e.inAnim = i;
			elementChanged();
			previewOne();
		}, s);
		sliderRow(p, getString(R.string.tr_in_ms), 0, 3000, () -> e.inMs, v -> {
			e.inMs = v;
			elementChanged();
		}, v -> v + " ms", s);
		sliderRow(p, getString(R.string.tr_delay), 0, 3000, () -> e.delay, v -> {
			e.delay = v;
			elementChanged();
		}, v -> v + " ms", s);
		flowRow(p, getString(R.string.tr_out), null, animNames(), () -> e.outAnim, i -> {
			e.outAnim = i;
			elementChanged();
		}, s);
		sliderRow(p, getString(R.string.tr_out_ms), 0, 3000, () -> e.outMs, v -> {
			e.outMs = v;
			elementChanged();
		}, v -> v + " ms", s);

		actionRow(p, getString(R.string.tr_remove), getString(R.string.tr_remove_sub), R.drawable.delete, () ->
				UiUtils.showQuestion(requireContext(), R.string.tr_remove, R.string.tr_remove_question, R.drawable.delete).onSuccess(ok -> {
					cfg.remove(e);
					selectedId = 0;
					cfg.save(car);
					if (preview != null) {
						preview.setSelected(0);
						preview.refresh();
					}
					refreshElementChips();
					buildPanel();
				}));
	}

	private void elementChanged() {
		cfg.save(car);
		if (preview != null) preview.refresh();
		for (Runnable r : new ArrayList<>(panelSync)) r.run();
	}

	/** Shows the entrance of the whole screen once, after an animation was picked. */
	private void previewOne() {
		playPreview();
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
