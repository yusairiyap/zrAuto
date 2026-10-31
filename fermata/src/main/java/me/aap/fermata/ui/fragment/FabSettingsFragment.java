package me.aap.fermata.ui.fragment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import android.widget.TextView;

/**
 * Settings &gt; Interface &gt; Floating Action Button (and its Android Auto twin): the floating
 * buttons as rows to drag into the order they sit in. Each row has a switch (shown or not) and a menu
 * to change what the button does or remove it; above them, the options all the buttons share (one
 * pill, draggable, size), and below them an Add button for as many more as fit the screen.
 * <p>
 * The buttons are slots (FAB2 to FAB10), each with its own "shown" and "action" settings; the first
 * {@code FAB_COUNT} of them are in use. Dragging a row swaps those two settings between the slots,
 * adding takes the next slot into use, and removing shifts the later slots up.
 */
public class FabSettingsFragment extends ReorderListFragment {
	private static final int FIRST_SLOT = 2;
	/** One button's width in dp at size 1.0 (a Material FAB), see {@link #maxExtra()}. */
	private static final float FAB_DP = 56f;
	@Nullable
	private TextView addButton;
	/** Editing the car screen's own buttons (Settings &gt; Interface &gt; Android Auto). */
	private boolean aa;

	@Override
	public int settingsEntryTitle() {
		return R.string.layout_settings;
	}

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
		return pref(MainActivityPrefs.fabEnabled(slot));
	}

	private Pref<IntSupplier> actionPref(int slot) {
		return pref(MainActivityPrefs.fabAction(slot));
	}

	/** The extra buttons in use: slots FIRST_SLOT to {@link #lastSlot()}. */
	private int count() {
		int n = prefs().getIntPref(pref(MainActivityPrefs.FAB_COUNT));
		return Math.max(0, Math.min(MainActivityPrefs.FAB_COUNT_MAX, n));
	}

	private int lastSlot() {
		return FIRST_SLOT + count() - 1;
	}

	/**
	 * How many extra buttons fit this screen's width side by side at the chosen size (one more
	 * than that would run off its edge), but never fewer than the buttons already in use.
	 */
	private int maxExtra() {
		android.util.DisplayMetrics dm = delegate().getContext().getResources().getDisplayMetrics();
		float widthDp = dm.widthPixels / dm.density;
		float size = Math.max(0.5f, prefs().getFloatPref(pref(MainActivityPrefs.FAB_SIZE)));
		// The primary button counts, and so do the screen-edge margin and the nav bar's side room.
		int fit = (int) ((widthDp - 48f) / (FAB_DP * size)) - 1;
		return Math.max(count(), Math.min(MainActivityPrefs.FAB_COUNT_MAX, fit));
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
		for (int slot = FIRST_SLOT; slot <= lastSlot(); slot++) {
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
		int n = count();
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
			b.addItem(R.id.fab_remove_button, me.aap.utils.R.drawable.close, R.string.fab_remove)
					.setHandler(i -> {
						removeSlot(slot);
						return true;
					});
		});
	}

	@Override
	protected void onReset() {
		MainActivityPrefs p = prefs();
		try (var e = p.editPreferenceStore()) {
			for (int slot = FIRST_SLOT; slot < FIRST_SLOT + MainActivityPrefs.FAB_COUNT_MAX; slot++) {
				e.removePref(enabledPref(slot));
				e.removePref(actionPref(slot));
			}
			e.removePref(pref(MainActivityPrefs.FAB_COUNT));
		}
	}

	/** Takes the next slot into use, set to an action none of the buttons does yet. */
	private void addSlot() {
		int n = count();
		if (n >= maxExtra()) return;
		int slot = FIRST_SLOT + n;
		Action pick = Action.PLAY_PAUSE;
		for (Action act : ActionFabMediator.OFFERED_ACTIONS) {
			boolean used = false;
			for (int s = FIRST_SLOT; s < slot; s++) used |= (actionOf(s) == act);
			if (!used) {
				pick = act;
				break;
			}
		}
		try (var e = prefs().editPreferenceStore()) {
			e.setBooleanPref(enabledPref(slot), true);
			e.setIntPref(actionPref(slot), pick.ordinal());
			e.setIntPref(pref(MainActivityPrefs.FAB_COUNT), n + 1);
		}
		reload();
	}

	/** Drops {@code slot}'s button: the later ones move up a place and the last slot goes unused. */
	private void removeSlot(int slot) {
		int last = lastSlot();
		int n = count();
		try (var e = prefs().editPreferenceStore()) {
			for (int s = slot; s < last; s++) {
				e.setBooleanPref(enabledPref(s), prefs().getBooleanPref(enabledPref(s + 1)));
				e.setIntPref(actionPref(s), prefs().getIntPref(actionPref(s + 1)));
			}
			e.removePref(enabledPref(last));
			e.removePref(actionPref(last));
			e.setIntPref(pref(MainActivityPrefs.FAB_COUNT), Math.max(0, n - 1));
		}
		reload();
	}

	@Override
	protected View createFooterExtra() {
		TextView t = text(R.string.fab_add, 15, accent, true);
		t.setGravity(android.view.Gravity.CENTER);
		t.setSingleLine(true);
		t.setBackground(pressable(rounded(tileFill, 24)));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> addSlot());
		addButton = t;
		return t;
	}

	@Override
	protected void onReloaded() {
		TextView t = addButton;
		if (t == null) return;
		int room = maxExtra() - count();
		t.setEnabled(room > 0);
		t.setAlpha(room > 0 ? 1f : 0.45f);
		t.setText((room > 0) ? getString(R.string.fab_add_room, room) : getString(R.string.fab_add_full));
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
		delegate().showFragment(R.id.layout_settings_fragment, aa);
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
