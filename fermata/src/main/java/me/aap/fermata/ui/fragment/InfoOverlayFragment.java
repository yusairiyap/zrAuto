package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.aap.utils.function.BooleanSupplier;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.data.DataUsageAddon;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.InfoOverlayView;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Settings &gt; Interface &gt; Info overlay: where it sits and how big, above the items it can show
 * (clock, battery, temperature, distance, data, downloaded status), to drag into the order they
 * are laid out in. Each has a switch (shown or not) and a menu for its icon.
 */
public class InfoOverlayFragment extends ReorderListFragment {
	private final List<TextView> posChips = new ArrayList<>();

	@Override
	public int getFragmentId() {
		return R.id.info_overlay_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.info_overlay_prefs);
	}

	private MainActivityPrefs prefs() {
		return delegate().getPrefs();
	}

	// ---- Which pref belongs to which item ----

	private static Pref<? extends BooleanSupplier> shownPref(String key) {
		return switch (key) {
			case "downloaded" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DOWNLOADED;
			case "clock" -> MainActivityPrefs.INFO_OVERLAY_SHOW_CLOCK;
			case "battery" -> MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_PCT;
			case "temp" -> MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_TEMP;
			case "distance" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DISTANCE;
			case "data_usage" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_USAGE;
			default -> MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_REMAINING;
		};
	}

	/** The item's icon switch; null for the one that is only an icon. */
	@Nullable
	private static Pref<? extends BooleanSupplier> iconPref(String key) {
		return switch (key) {
			case "clock" -> MainActivityPrefs.INFO_OVERLAY_SHOW_CLOCK_ICON;
			case "battery" -> MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_ICON;
			case "temp" -> MainActivityPrefs.INFO_OVERLAY_SHOW_TEMP_ICON;
			case "distance" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DISTANCE_ICON;
			case "data_usage", "data_remaining" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_ICON;
			default -> null;
		};
	}

	@DrawableRes
	private static int iconOf(String key) {
		return switch (key) {
			case "downloaded" -> R.drawable.download;
			case "clock" -> R.drawable.clock;
			case "battery" -> R.drawable.battery;
			case "temp" -> R.drawable.thermometer;
			case "distance" -> R.drawable.distance;
			case "data_usage" -> R.drawable.data_usage;
			default -> R.drawable.data_remaining;
		};
	}

	@StringRes
	private static int nameOf(String key) {
		return switch (key) {
			case "downloaded" -> R.string.info_item_downloaded;
			case "clock" -> R.string.info_item_clock;
			case "battery" -> R.string.info_item_battery;
			case "temp" -> R.string.info_item_temp;
			case "distance" -> R.string.info_item_distance;
			case "data_usage" -> R.string.info_item_data_usage;
			default -> R.string.info_item_data_remaining;
		};
	}

	private static boolean dataAddonOn() {
		AddonManager m = AddonManager.get();
		return (m != null) && (m.getAddon(DataUsageAddon.class) != null);
	}

	/** The items this phone can show: the data ones only while the Data Usage addon is on. */
	private static List<String> available() {
		boolean data = dataAddonOn();
		List<String> keys = new ArrayList<>();
		for (String k : InfoOverlayView.ITEM_KEYS) {
			if (!k.startsWith("data_") || data) keys.add(k);
		}
		return keys;
	}

	// ---- Rows ----

	@Override
	protected List<Row> loadRows() {
		Context ctx = delegate().getContext();
		MainActivityPrefs p = prefs();
		List<String> offered = available();
		List<Row> rows = new ArrayList<>();
		for (String key : InfoOverlayView.parseOrder(p.getInfoOverlayOrderPref())) {
			if (!offered.contains(key)) continue;
			boolean shown = p.getBooleanPref(shownPref(key));
			Pref<? extends BooleanSupplier> ip = iconPref(key);
			String state = ctx.getString(shown ? R.string.nav_tab_shown : R.string.nav_tab_hidden);
			if (ip != null) {
				state += " · " + ctx.getString(p.getBooleanPref(ip) ? R.string.info_row_icon_on :
						R.string.info_row_icon_off);
			} else {
				state += " · " + ctx.getString(R.string.info_row_icon_only);
			}
			rows.add(new Row(key, iconOf(key), ctx.getString(nameOf(key)), state, shown));
		}
		return rows;
	}

	@Override
	protected void saveOrder(List<String> keys) {
		// The items not offered right now (the data ones, with the addon off) keep their place after.
		List<String> all = new ArrayList<>(keys);
		for (String k : InfoOverlayView.parseOrder(prefs().getInfoOverlayOrderPref())) {
			if (!all.contains(k)) all.add(k);
		}
		prefs().applyStringPref(MainActivityPrefs.INFO_OVERLAY_ORDER, String.join(",", all));
	}

	@Override
	protected void setChecked(String key, boolean checked) {
		prefs().applyBooleanPref(shownPref(key), checked);
	}

	@Override
	protected void showRowMenu(Row row) {
		MainActivityDelegate a = delegate();
		MainActivityPrefs p = prefs();
		String key = row.key;
		boolean shown = row.checked;
		Pref<? extends BooleanSupplier> ip = iconPref(key);
		a.getContextMenu().show(b -> {
			b.setTitle(row.title.toString());
			b.addItem(View.generateViewId(),
					shown ? me.aap.utils.R.drawable.check_box : me.aap.utils.R.drawable.check_box_blank,
					shown ? R.string.info_item_hide : R.string.info_item_show).setHandler(i -> {
				p.applyBooleanPref(shownPref(key), !shown);
				reload();
				return true;
			});
			if (ip != null) {
				boolean icon = p.getBooleanPref(ip);
				b.addItem(View.generateViewId(),
						icon ? me.aap.utils.R.drawable.check_box : me.aap.utils.R.drawable.check_box_blank,
						icon ? R.string.info_icon_hide : R.string.info_icon_show).setHandler(i -> {
					p.applyBooleanPref(ip, !icon);
					reload();
					return true;
				});
			}
		});
	}

	@Override
	protected void onReset() {
		MainActivityPrefs p = prefs();
		try (var e = p.editPreferenceStore()) {
			for (Pref<?> pref : MainActivityPrefs.INFO_OVERLAY_PREFS) e.removePref(pref);
		}
		syncOptions();
	}

	@Override
	protected int resetTitle() {
		return R.string.info_overlay_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.info_overlay_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.info_overlay_hint;
	}

	@Override
	protected void goBack() {
		MainActivityDelegate a = delegate();
		a.showFragment(R.id.settings_fragment, a.isCarActivity() ? SettingsFragment.SHOW_AA_INTERFACE :
				SettingsFragment.SHOW_INTERFACE);
	}

	@Override
	protected void onReloaded() {
		syncOptions();
	}

	// ---- Options above the rows: position, only with the panel, size ----

	@Nullable
	@Override
	protected View createExtraHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);

		section(h, R.string.clock_pos);
		LinearLayout chips = new LinearLayout(palette);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		posChips.clear();
		int[] positions = {MainActivityPrefs.CLOCK_POS_NONE, MainActivityPrefs.CLOCK_POS_LEFT,
				MainActivityPrefs.CLOCK_POS_CENTER, MainActivityPrefs.CLOCK_POS_RIGHT};
		int[] names = {R.string.clock_pos_none, R.string.clock_pos_left, R.string.clock_pos_center,
				R.string.clock_pos_right};
		for (int i = 0; i < positions.length; i++) {
			int pos = positions[i];
			TextView c = new TextView(palette);
			c.setText(names[i]);
			c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
			c.setTypeface(Typeface.DEFAULT_BOLD);
			c.setGravity(Gravity.CENTER);
			c.setSingleLine(true);
			c.setPadding(dp(6), dp(12), dp(6), dp(12));
			c.setTag(pos);
			GradientDrawable fill = rounded(tileFill, 16);
			c.setTag(R.id.info_overlay_fragment, fill);
			c.setBackground(pressable(fill));
			c.setClickable(true);
			c.setFocusable(true);
			c.setOnClickListener(v -> {
				prefs().applyIntPref(MainActivityPrefs.CLOCK_POS, pos);
				syncOptions();
			});
			posChips.add(c);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : dp(4), 0, (i == positions.length - 1) ? 0 : dp(4), 0);
			chips.addView(c, lp);
		}
		h.addView(chips, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		addSwitchRow(h, R.string.info_overlay_only_when_control_panel_visible, 0,
				() -> prefs().getBooleanPref(MainActivityPrefs.INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE),
				on -> prefs().applyBooleanPref(MainActivityPrefs.INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE, on),
				null);
		addSliderRow(h, R.string.info_overlay_size, 10, 40, 0.05f,
				() -> prefs().getFloatPref(MainActivityPrefs.INFO_OVERLAY_SIZE),
				v -> prefs().applyFloatPref(MainActivityPrefs.INFO_OVERLAY_SIZE, (float) v));

		section(h, R.string.info_overlay_items);
		TextView hint = text(R.string.info_overlay_items_hint, 13, textSecondary, false);
		hint.setPadding(dp(4), 0, dp(4), dp(4));
		h.addView(hint);
		return h;
	}

	/** The options above the rows show what is set (also after a Reset). */
	private void syncOptions() {
		MainActivityPrefs p = prefs();
		int pos = p.getIntPref(MainActivityPrefs.CLOCK_POS);
		for (TextView c : posChips) {
			boolean sel = ((Integer) c.getTag()) == pos;
			GradientDrawable fill = (GradientDrawable) c.getTag(R.id.info_overlay_fragment);
			fill.setColor(sel ? accent : tileFill);
			c.setTextColor(sel ? onAccent : textPrimary);
		}
	}

	@Override
	public void onDestroyView() {
		posChips.clear();
		super.onDestroyView();
	}
}
