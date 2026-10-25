package me.aap.fermata.ui.home;

import me.aap.fermata.ui.home.widget.HomeWidgets;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.EffectsUi;

/** The two cards Home's edit mode opens: the list of widgets to add, and the background's settings. */
final class HomePickers {
	private HomePickers() {
	}

	/** Every kind of widget with what it does and its first size; a tap adds one. */
	static void showAdd(HomeHost host, Consumer<HomeWidgetType> onPick) {
		HomePalette p = host.palette();
		Context ctx = p.ctx;
		LinearLayout list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		for (HomeWidgetType t : HomeWidgets.all()) {
			boolean ok = t.isAvailable();
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(HomeUi.dp(ctx, 8), HomeUi.dp(ctx, 8), HomeUi.dp(ctx, 8), HomeUi.dp(ctx, 8));
			row.setBackground(HomeUi.pressable(p.chipRipple, p.rowFill, HomeUi.dp(ctx, 18)));
			if (!ok) row.setAlpha(0.5f);

			FrameLayout disc = new FrameLayout(ctx);
			disc.setBackground(HomeUi.oval(HomeUi.alpha(t.accent, 0.25f)));
			ImageView ic = new ImageView(ctx);
			ic.setImageResource(t.icon);
			ic.setImageTintList(ColorStateList.valueOf(t.accent));
			disc.addView(ic, new FrameLayout.LayoutParams(HomeUi.dp(ctx, 22), HomeUi.dp(ctx, 22), Gravity.CENTER));
			row.addView(disc, new LinearLayout.LayoutParams(HomeUi.dp(ctx, 44), HomeUi.dp(ctx, 44)));

			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			TextView name = new TextView(ctx);
			name.setText(t.name);
			name.setTextColor(p.textPrimary);
			name.setTextSize(15);
			name.setTypeface(Typeface.DEFAULT_BOLD);
			texts.addView(name);
			TextView desc = new TextView(ctx);
			desc.setText(ok ? ctx.getString(t.description) : ctx.getString(R.string.home_widget_off));
			desc.setTextColor(p.textSecondary);
			desc.setTextSize(12.5f);
			texts.addView(desc);
			LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
					ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			tlp.setMarginStart(HomeUi.dp(ctx, 12));
			row.addView(texts, tlp);

			TextView size = new TextView(ctx);
			size.setText(t.defW + "×" + t.defH);
			size.setTextColor(p.textTertiary);
			size.setTextSize(12);
			size.setPadding(HomeUi.dp(ctx, 8), 0, HomeUi.dp(ctx, 4), 0);
			row.addView(size);

			if (ok) {
				row.setClickable(true);
				row.setFocusable(true);
				row.setOnClickListener(v -> {
					HomeModal.dismissNow();
					onPick.accept(t);
				});
			}
			LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			rlp.bottomMargin = HomeUi.dp(ctx, 6);
			list.addView(row, rlp);
		}
		HomeModal.show(host, ctx.getString(R.string.home_add_widget), R.drawable.home_add, list, null);
	}

	/** The picture behind Home: choose or remove it, and how dark and how blurred it is. */
	static void showBackground(HomeHost host) {
		HomePalette p = host.palette();
		Context ctx = p.ctx;
		MainActivityDelegate a = host.activity();
		MainActivityPrefs prefs = a.getPrefs();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);

		// What the background is: a picture from the phone, or the cover of what plays.
		ChipFlowLayout types = new ChipFlowLayout(ctx);
		types.setPadding(0, 0, 0, HomeUi.dp(ctx, 4));
		box.addView(types);

		TextView hint = new TextView(ctx);
		hint.setTextColor(p.textSecondary);
		hint.setTextSize(13);
		box.addView(hint);

		ChipFlowLayout chips = new ChipFlowLayout(ctx);
		chips.setPadding(0, HomeUi.dp(ctx, 12), 0, HomeUi.dp(ctx, 8));
		chips.addView(ChipFlowLayout.chip(ctx, R.drawable.home_image, R.string.home_bg_choose, true,
				() -> {
					HomeModal.dismissNow();
					HomeBackground.pick(a, null);
				}));
		if (HomeBackground.exists(ctx)) {
			chips.addView(ChipFlowLayout.chip(ctx, R.drawable.delete, R.string.home_bg_remove, false, () -> {
				HomeModal.dismissNow();
				HomeBackground.clear(a);
			}));
		}
		box.addView(chips);
		Runnable[] refill = new Runnable[1];
		refill[0] = () -> {
			int src = prefs.getIntPref(MainActivityPrefs.HOME_BG_SOURCE);
			types.removeAllViews();
			types.addView(ChipFlowLayout.chip(ctx, R.drawable.home_image, R.string.home_bg_source_picture, src == 0, () -> {
				prefs.applyIntPref(MainActivityPrefs.HOME_BG_SOURCE, 0);
				refill[0].run();
			}));
			types.addView(ChipFlowLayout.chip(ctx, R.drawable.music, R.string.home_bg_source_cover, src == 1, () -> {
				prefs.applyIntPref(MainActivityPrefs.HOME_BG_SOURCE, 1);
				refill[0].run();
			}));
			hint.setText(src == 0 ? R.string.home_bg_hint : R.string.home_bg_hint_cover);
			chips.setVisibility(src == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
		};
		refill[0].run();

		LayoutInflater inf = LayoutInflater.from(ctx);
		EffectsUi.Row dim = EffectsUi.addRow(inf, box, R.string.home_bg_dim, 80, 5, v -> v + "%",
				v -> prefs.applyIntPref(MainActivityPrefs.HOME_BG_DIM, v));
		dim.set(Math.min(80, prefs.getIntPref(MainActivityPrefs.HOME_BG_DIM)));
		EffectsUi.Row zoom = EffectsUi.addRow(inf, box, R.string.home_bg_zoom, 300, 5, v -> Math.max(100, v) + "%",
				v -> prefs.applyIntPref(MainActivityPrefs.HOME_BG_ZOOM, Math.max(100, v)));
		zoom.set(Math.max(100, prefs.getIntPref(MainActivityPrefs.HOME_BG_ZOOM)));
		EffectsUi.Row blur = EffectsUi.addRow(inf, box, R.string.home_bg_blur, 100, 5,
				v -> (v == 0) ? ctx.getString(R.string.home_bg_blur_off) : v + "%",
				v -> prefs.applyIntPref(MainActivityPrefs.HOME_BG_BLUR, v));
		blur.set(prefs.getIntPref(MainActivityPrefs.HOME_BG_BLUR));
		HomeModal.show(host, ctx.getString(R.string.home_background), R.drawable.home_image, box, null);
	}
}
