package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.ThemePreviewView;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Settings &gt; Appearance &gt; Theme: a gallery of small phones drawn from each theme's own colours, a tick on the one in use,
 * and the colour-from-album-art switch. With Android Auto built in, the two pills pick the phone's or the car screen's theme.
 */
public class ThemeSettingsFragment extends ReorderListFragment {
	private boolean car;
	private LinearLayout gallery;
	private final List<Runnable> refreshers = new ArrayList<>();

	private record Theme(int id, @StringRes int name, @StyleRes int style) {
	}

	private static List<Theme> themes() {
		List<Theme> l = new ArrayList<>();
		l.add(new Theme(MainActivityPrefs.THEME_CLASSIC, R.string.theme_classic, R.style.AppTheme_Classic));
		l.add(new Theme(MainActivityPrefs.THEME_DARK, R.string.theme_dark, R.style.AppTheme_Dark));
		l.add(new Theme(MainActivityPrefs.THEME_LIGHT, R.string.theme_light, R.style.AppTheme_Light));
		l.add(new Theme(MainActivityPrefs.THEME_BLACK, R.string.theme_black, R.style.AppTheme_Black));
		l.add(new Theme(MainActivityPrefs.THEME_PURPLE, R.string.theme_purple, R.style.AppTheme_Purple));
		l.add(new Theme(MainActivityPrefs.THEME_STAR_WARS, R.string.theme_star_wars, R.style.AppTheme_BlackStarWars));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			l.add(new Theme(MainActivityPrefs.THEME_DYNAMIC, R.string.theme_dynamic, R.style.AppTheme_Dynamic));
		}
		// Follows the phone's day and night: drawn as the dark one.
		l.add(new Theme(MainActivityPrefs.THEME_SYSTEM, R.string.theme_system, R.style.AppTheme_Dark));
		return l;
	}

	/** The category in the list beside the page (a wide window) that this page belongs to. */
	@Override
	public int settingsEntryTitle() {
		return R.string.appearance_prefs;
	}

	@Override
	public int getFragmentId() {
		return R.id.theme_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.theme);
	}

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

	private Pref<me.aap.utils.function.IntSupplier> pref() {
		return car ? MainActivityPrefs.THEME_AA : MainActivityPrefs.THEME_MAIN;
	}

	@Override
	protected void onReset() {
		var p = delegate().getPrefs();
		try (var e = p.editPreferenceStore()) {
			e.removePref(MainActivityPrefs.THEME_MAIN);
			if (BuildConfig.AUTO) e.removePref(MainActivityPrefs.THEME_AA);
		}
		for (Runnable r : refreshers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.theme_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.theme_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.theme_settings_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		var prefs = delegate().getPrefs();
		car = BuildConfig.AUTO && delegate().isCarActivity();

		if (BuildConfig.AUTO) {
			LinearLayout pills = new LinearLayout(palette);
			pills.setOrientation(LinearLayout.HORIZONTAL);
			TextView[] tabs = new TextView[2];
			int[] labels = {R.string.settings_screen_phone, R.string.settings_screen_car};
			Runnable light = () -> {
				for (int i = 0; i < 2; i++) {
					boolean on = (i == 1) == car;
					tabs[i].setTextColor(on ? onAccent : textPrimary);
					fillColor((GradientDrawable) tabs[i].getTag(R.id.settings_row_tag), on ? accent : tileFill);
				}
			};
			for (int i = 0; i < 2; i++) {
				boolean carTab = i == 1;
				TextView t = text(labels[i], 14, textPrimary, true);
				t.setGravity(Gravity.CENTER);
				t.setPadding(dp(12), dp(13), dp(12), dp(13));
				GradientDrawable fill = rounded(tileFill, 16);
				t.setTag(R.id.settings_row_tag, fill);
				t.setBackground(pressable(fill));
				t.setClickable(true);
				t.setFocusable(true);
				t.setOnClickListener(v -> {
					car = carTab;
					light.run();
					for (Runnable r : refreshers) r.run();
				});
				tabs[i] = t;
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
				lp.setMargins(carTab ? dp(4) : 0, dp(6), carTab ? 0 : dp(4), 0);
				pills.addView(t, lp);
			}
			light.run();
			box.addView(pills, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		}

		section(box, R.string.theme_settings_gallery);
		HorizontalScrollView hs = new HorizontalScrollView(palette);
		hs.setHorizontalScrollBarEnabled(false);
		hs.setClipToPadding(false);
		gallery = new LinearLayout(palette);
		gallery.setOrientation(LinearLayout.HORIZONTAL);
		for (Theme th : themes()) gallery.addView(card(th), cardParams());
		hs.addView(gallery, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		hp.topMargin = dp(10);
		box.addView(hs, hp);

		section(box, R.string.theme_settings_colour);
		addSwitchRow(box, R.string.art_accent, R.string.art_accent_sub,
				() -> prefs.getBooleanPref(MainActivityPrefs.ART_ACCENT),
				on -> prefs.applyBooleanPref(MainActivityPrefs.ART_ACCENT, on), null);
		refreshers.add(() -> {
		});
		for (Runnable r : syncers) r.run();
		for (Runnable r : refreshers) r.run();
		return box;
	}

	private LinearLayout.LayoutParams cardParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), WRAP_CONTENT);
		lp.setMarginEnd(dp(10));
		return lp;
	}

	private View card(Theme th) {
		LinearLayout c = new LinearLayout(palette);
		c.setOrientation(LinearLayout.VERTICAL);
		c.setPadding(dp(8), dp(8), dp(8), dp(10));
		GradientDrawable fill = rounded(tileFill, 16);
		c.setBackground(pressable(fill));
		c.setClickable(true);
		c.setFocusable(true);
		ThemePreviewView pv = new ThemePreviewView(palette, th.style());
		c.addView(pv, new LinearLayout.LayoutParams(MATCH_PARENT, dp(170)));
		TextView name = text(th.name(), 13.5f, textPrimary, true);
		name.setGravity(Gravity.CENTER);
		name.setSingleLine(true);
		name.setPadding(0, dp(8), 0, 0);
		c.addView(name);
		c.setOnClickListener(v -> {
			delegate().getPrefs().applyIntPref(pref(), th.id());
			for (Runnable r : refreshers) r.run();
		});
		refreshers.add(() -> {
			boolean on = delegate().getPrefs().getIntPref(pref()) == th.id();
			fillColor(fill, on ? accent : tileFill);
			name.setTextColor(on ? onAccent : textPrimary);
		});
		return c;
	}

	@Override
	public void onDestroyView() {
		refreshers.clear();
		gallery = null;
		super.onDestroyView();
	}
}
