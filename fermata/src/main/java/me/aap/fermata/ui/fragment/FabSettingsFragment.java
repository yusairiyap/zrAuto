package me.aap.fermata.ui.fragment;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;

import android.view.View;
import android.widget.LinearLayout;

/**
 * Settings &gt; Interface &gt; Floating Action Button (and its Android Auto twin): the floating
 * buttons as rows to drag into the order they sit in. Each row has a switch (shown or not) and a menu
 * to change what the button does; above them, the options all the buttons share (one pill,
 * draggable, size).
 * <p>
 * The buttons are fixed slots (FAB2 to FAB6), each with its own "shown" and "action" settings, so
 * dragging a row swaps those two settings between the slots.
 */
public class FabSettingsFragment extends ReorderListFragment {
	private static final int FIRST_SLOT = 2;
	private static final int LAST_SLOT = 6;
	/** Editing the car screen's own buttons (Settings &gt; Interface &gt; Android Auto). */
	private boolean aa;

	@Override
	public int getFragmentId() {
		return R.id.fab_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		String t = getString(R.string.secondary_fab_prefs);
		return aa ? t + " · " + getString(R.string.interface_prefs_aa) : t;
	}

	/** @param input {@code true} for the car screen's own buttons */
	@Override
	public void setInput(Object input) {
		boolean car = BuildConfig.AUTO && Boolean.TRUE.equals(input);
		if (car == aa) return;
		aa = car;
		if (getView() != null) reload();
	}

	private MainActivityPrefs prefs() {
		return delegate().getPrefs();
	}

	@SuppressWarnings("unchecked")
	private <S> Pref<S> pref(Pref<S> p) {
		if (!aa) return p;
		Pref<?> twin = MainActivityPrefs.fabAA(p);
		return (twin == null) ? p : (Pref<S>) twin;
	}

	private Pref<BooleanSupplier> enabledPref(int slot) {
		return pref(switch (slot) {
			case 2 -> MainActivityPrefs.FAB2_ENABLED;
			case 3 -> MainActivityPrefs.FAB3_ENABLED;
			case 4 -> MainActivityPrefs.FAB4_ENABLED;
			case 5 -> MainActivityPrefs.FAB5_ENABLED;
			default -> MainActivityPrefs.FAB6_ENABLED;
		});
	}

	private Pref<IntSupplier> actionPref(int slot) {
		return pref(switch (slot) {
			case 2 -> MainActivityPrefs.FAB2_ACTION;
			case 3 -> MainActivityPrefs.FAB3_ACTION;
			case 4 -> MainActivityPrefs.FAB4_ACTION;
			case 5 -> MainActivityPrefs.FAB5_ACTION;
			default -> MainActivityPrefs.FAB6_ACTION;
		});
	}

	@NonNull
	private Action actionOf(int slot) {
		Action a = Action.get(prefs().getIntPref(actionPref(slot)));
		return (a == null) ? Action.NONE : a;
	}

	// ---- Rows ----

	@Override
	protected List<Row> loadRows() {
		Context ctx = delegate().getContext();
		List<Row> rows = new ArrayList<>();
		for (int slot = FIRST_SLOT; slot <= LAST_SLOT; slot++) {
			Action act = actionOf(slot);
			boolean shown = prefs().getBooleanPref(enabledPref(slot));
			String state = ctx.getString(shown ? R.string.nav_tab_shown : R.string.nav_tab_hidden);
			rows.add(new Row(Integer.toString(slot), ActionFabMediator.iconFor(delegate(), act),
					ctx.getString(act.getName()), state, shown));
		}
		return rows;
	}

	/** The rows' new order: slot {@code i} now holds the button that was dragged to place {@code i}. */
	@Override
	protected void saveOrder(List<String> keys) {
		MainActivityPrefs p = prefs();
		int n = LAST_SLOT - FIRST_SLOT + 1;
		boolean[] shown = new boolean[n];
		int[] action = new int[n];
		for (int i = 0; i < n; i++) {
			shown[i] = p.getBooleanPref(enabledPref(FIRST_SLOT + i));
			action[i] = p.getIntPref(actionPref(FIRST_SLOT + i));
		}
		try (var e = p.editPreferenceStore()) {
			for (int i = 0; i < n && i < keys.size(); i++) {
				int from = Integer.parseInt(keys.get(i)) - FIRST_SLOT;
				e.setBooleanPref(enabledPref(FIRST_SLOT + i), shown[from]);
				e.setIntPref(actionPref(FIRST_SLOT + i), action[from]);
			}
		}
		// The slots now hold other buttons: the rows are named after them.
		reload();
	}

	@Override
	protected void setChecked(String key, boolean checked) {
		prefs().applyBooleanPref(enabledPref(Integer.parseInt(key)), checked);
	}

	@Override
	protected void showRowMenu(Row row) {
		MainActivityDelegate a = delegate();
		int slot = Integer.parseInt(row.key);
		boolean shown = row.checked;
		a.getContextMenu().show(b -> {
			b.setTitle(row.title.toString());
			b.addItem(R.id.fab_change_action, R.drawable.fab, R.string.fab_change_action).setSubmenu(sb -> {
				sb.setTitle(getString(R.string.fab_change_action));
				sb.setSelectionHandler(item -> {
					Action act = item.getData();
					if (act == null) return true;
					prefs().applyIntPref(actionPref(slot), act.ordinal());
					reload();
					return true;
				});
				for (Action act : ActionFabMediator.OFFERED_ACTIONS) {
					sb.addItem(View.generateViewId(), ActionFabMediator.iconFor(a, act), act.getName())
							.setData(act);
				}
			});
			b.addItem(View.generateViewId(),
					shown ? me.aap.utils.R.drawable.check_box : me.aap.utils.R.drawable.check_box_blank,
					shown ? R.string.fab_hide : R.string.fab_show).setHandler(i -> {
				prefs().applyBooleanPref(enabledPref(slot), !shown);
				reload();
				return true;
			});
		});
	}

	@Override
	protected void onReset() {
		MainActivityPrefs p = prefs();
		try (var e = p.editPreferenceStore()) {
			for (int slot = FIRST_SLOT; slot <= LAST_SLOT; slot++) {
				e.removePref(enabledPref(slot));
				e.removePref(actionPref(slot));
			}
		}
	}

	@Override
	protected int resetTitle() {
		return R.string.fab_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.fab_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.fab_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment,
				aa ? SettingsFragment.SHOW_AA_INTERFACE : SettingsFragment.SHOW_INTERFACE);
	}

	// ---- Options above the rows ----

	@Override
	protected View createExtraHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);
		MainActivityPrefs p = prefs();

		addSwitchRow(h, R.string.fab_pill, R.string.fab_pill_sub,
				() -> p.getBooleanPref(MainActivityPrefs.FAB_PILL),
				on -> p.applyBooleanPref(MainActivityPrefs.FAB_PILL, on), null);
		// Ruled out as one pill (a dragged button would stretch it): greyed out, and off.
		addSwitchRow(h, R.string.fab_draggable, R.string.fab_draggable_sub,
				() -> p.getBooleanPref(pref(MainActivityPrefs.FAB_DRAGGABLE)),
				on -> p.applyBooleanPref(pref(MainActivityPrefs.FAB_DRAGGABLE), on),
				() -> !p.getBooleanPref(MainActivityPrefs.FAB_PILL));
		addSliderRow(h, R.string.fab_size, 10, 40, 0.05f,
				() -> p.getFloatPref(pref(MainActivityPrefs.FAB_SIZE)),
				v -> p.applyFloatPref(pref(MainActivityPrefs.FAB_SIZE), (float) v));

		section(h, R.string.fab_buttons);
		return h;
	}
}
