package me.aap.utils.pref;

import static me.aap.utils.collection.CollectionUtils.comparing;
import static me.aap.utils.collection.CollectionUtils.mapToArray;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.aap.utils.app.App;
import me.aap.utils.function.Consumer;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.voice.TextToSpeech;

/**
 * @author Andrey Pavlenko
 */
public class PreferenceSet implements Supplier<PreferenceView.Opts> {
	private static Locale[] ttsLocales;
	final List<Supplier<? extends PreferenceView.Opts>> preferences = new ArrayList<>();
	private final int id;
	private PreferenceSet parent;
	private final Consumer<PreferenceView.Opts> builder;
	private int idCounter = 1;
	private PreferenceViewAdapter adapter;
	private boolean carOnly;

	public PreferenceSet() {
		this(null, null);
	}

	private PreferenceSet(PreferenceSet parent, Consumer<PreferenceView.Opts> builder) {
		int id = 1;
		for (PreferenceSet p = parent; p != null; p = p.parent) {
			if (p.parent == null) {
				id = ++p.idCounter;
				break;
			}
		}
		this.id = id;
		this.parent = parent;
		this.builder = builder;
	}

	public int getId() {
		return id;
	}

	public PreferenceSet getParent() {
		return parent;
	}

	@Nullable
	public PreferenceSet find(int id) {
		if (id == getId()) return this;
		for (Supplier<? extends PreferenceView.Opts> s : preferences) {
			if (s instanceof PreferenceSet) {
				PreferenceSet p = ((PreferenceSet) s).find(id);
				if (p != null) return p;
			}
		}
		return null;
	}

	public List<Supplier<? extends PreferenceView.Opts>> getPreferences() {
		return preferences;
	}

	@Override
	public PreferenceView.Opts get() {
		PreferenceView.Opts opts = new PreferenceView.Opts();
		builder.accept(opts);
		if (carOnly) opts.carOnly = true;
		return opts;
	}

	public void addBooleanPref(Consumer<PreferenceView.BooleanOpts> builder) {
		add(() -> {
			PreferenceView.BooleanOpts o = new PreferenceView.BooleanOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addStringPref(Consumer<PreferenceView.StringOpts> builder) {
		add(() -> {
			PreferenceView.StringOpts o = new PreferenceView.StringOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addFilePref(Consumer<PreferenceView.FileOpts> builder) {
		add(() -> {
			PreferenceView.FileOpts o = new PreferenceView.FileOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addIntPref(Consumer<PreferenceView.IntOpts> builder) {
		add(() -> {
			PreferenceView.IntOpts o = new PreferenceView.IntOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addFloatPref(Consumer<PreferenceView.FloatOpts> builder) {
		add(() -> {
			PreferenceView.FloatOpts o = new PreferenceView.FloatOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addTimePref(Consumer<PreferenceView.TimeOpts> builder) {
		add(() -> {
			PreferenceView.TimeOpts o = new PreferenceView.TimeOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addListPref(Consumer<PreferenceView.ListOpts> builder) {
		add(() -> {
			PreferenceView.ListOpts o = new PreferenceView.ListOpts();
			builder.accept(o);
			return o;
		});
	}

	public void addLocalePref(Consumer<PreferenceView.LocaleOpts> builder) {
		addLocalePref(builder, false);
	}

	public void addTtsLocalePref(Consumer<PreferenceView.LocaleOpts> builder) {
		addLocalePref(builder, true);
	}

	private void addLocalePref(Consumer<PreferenceView.LocaleOpts> builder, boolean tts) {
		if (tts && (ttsLocales == null)) {
			TextToSpeech.create(App.get()).onSuccess(t -> {
				Set<Locale> locales = t.getAvailableLanguages();
				if (locales != null) ttsLocales = locales.toArray(new Locale[0]);
				t.close();
			});
		}
		add(() -> {
			PreferenceView.LocaleOpts lo = new PreferenceView.LocaleOpts();
			builder.accept(lo);
			Locale[] available = null;

			if (lo.locales != null) available = lo.locales.get();
			else if (ttsLocales != null) available = ttsLocales;
			if (available == null) available = Locale.getAvailableLocales();

			Locale[] locales = available;
			String cur = lo.store.getStringPref(lo.pref);
			Locale curLoc = (cur == null) ? Locale.getDefault() : Locale.forLanguageTag(cur);
			Arrays.sort(locales, comparing(Locale::getDisplayName));
			int currentIdx = -1;
			for (int i = 0; i < locales.length; i++) {
				if (locales[i].equals(curLoc)) {
					currentIdx = i;
					break;
				}
			}
			if (currentIdx == -1) {
				String lang = curLoc.getLanguage();
				curLoc = new Locale(lang);
				for (int i = 0; i < locales.length; i++) {
					if (locales[i].equals(curLoc)) {
						currentIdx = i;
						break;
					}
				}
				if (currentIdx == -1) {
					for (int i = 0; i < locales.length; i++) {
						if (locales[i].getLanguage().equals(lang)) {
							currentIdx = i;
							break;
						}
					}
				}
			}

			String[] values = mapToArray(Arrays.asList(locales), Locale::getDisplayName, String[]::new);
			PreferenceStore.Pref<IntSupplier> pref = PreferenceStore.Pref.i("L", currentIdx);
			BasicPreferenceStore ps = new BasicPreferenceStore();
			ps.addBroadcastListener((s, changed) -> lo.store.applyStringPref(lo.pref,
					locales[ps.getIntPref(pref)].toLanguageTag()));
			PreferenceView.ListOpts o = new PreferenceView.ListOpts();
			o.store = ps;
			o.pref = pref;
			o.stringValues = values;
			o.title = lo.title;
			o.subtitle = lo.subtitle;
			o.formatTitle = lo.formatTitle;
			o.formatSubtitle = lo.formatSubtitle;
			o.visibility = lo.visibility;
			return o;
		});
	}

	public void addButton(Consumer<PreferenceView.ButtonOpts> builder) {
		add(() -> {
			PreferenceView.ButtonOpts o = new PreferenceView.ButtonOpts();
			builder.accept(o);
			return o;
		});
	}

	public PreferenceSet subSet(Consumer<PreferenceView.Opts> builder) {
		PreferenceSet sub = new PreferenceSet(this, builder);
		add(sub);
		return sub;
	}

	/** The set among this one and its descendants that has {@code m} as a direct entry. */
	@Nullable
	private PreferenceSet ownerOf(Supplier<? extends PreferenceView.Opts> m) {
		if (preferences.contains(m)) return this;
		for (Supplier<? extends PreferenceView.Opts> s : preferences) {
			if (s instanceof PreferenceSet ps) {
				PreferenceSet o = ps.ownerOf(m);
				if (o != null) return o;
			}
		}
		return null;
	}

	/**
	 * Moves the given entries, wherever they are below this set, into a new sub-set of this one (in the order given),
	 * which takes the place of the first of them that was a direct entry (else it goes last). The moved sub-sets keep
	 * their ids, so saved states and deep links to them still resolve; only their parent changes.
	 *
	 * @return the new sub-set, or null when none of the entries was found
	 */
	@Nullable
	public PreferenceSet group(Consumer<PreferenceView.Opts> builder,
														 List<? extends Supplier<? extends PreferenceView.Opts>> members) {
		int at = -1;
		List<Supplier<? extends PreferenceView.Opts>> moved = new ArrayList<>();
		for (Supplier<? extends PreferenceView.Opts> m : members) {
			PreferenceSet owner = ownerOf(m);
			if (owner == null) continue;
			if (owner == this) {
				int i = preferences.indexOf(m);
				if ((at < 0) || (i < at)) at = i;
			}
			owner.preferences.remove(m);
			moved.add(m);
		}
		if (moved.isEmpty()) return null;
		PreferenceSet sub = new PreferenceSet(this, builder);
		for (Supplier<? extends PreferenceView.Opts> m : moved) {
			if (m instanceof PreferenceSet ps) ps.parent = sub;
			sub.preferences.add(m);
		}
		preferences.add((at < 0) ? preferences.size() : Math.min(at, preferences.size()), sub);
		return sub;
	}

	/** Marks this page's entry as applying to the car screen only. */
	public void markCarOnly() {
		carOnly = true;
	}

	/** Marks the row added last (not a sub-set: use {@link #markCarOnly()} on that) as applying to the car screen only. */
	public void markLastCarOnly() {
		int i = preferences.size() - 1;
		if (i < 0) return;
		Supplier<? extends PreferenceView.Opts> last = preferences.get(i);
		if (last instanceof PreferenceSet ps) {
			ps.carOnly = true;
			return;
		}
		preferences.set(i, () -> {
			PreferenceView.Opts o = last.get();
			o.carOnly = true;
			return o;
		});
	}

	public void addToView(RecyclerView v) {
		v.setHasFixedSize(true);
		v.setLayoutManager(new LinearLayoutManager(v.getContext()));
		v.setAdapter((adapter != null) ? adapter : new PreferenceViewAdapter(this));
	}

	public void addToMenu(OverlayMenu.Builder b, boolean setMinWidth) {
		addToMenu(b, setMinWidth ? (Resources.getSystem().getDisplayMetrics().widthPixels * 2 / 3) : 0);
	}

	/**
	 * @param minWidthPx minimum popup width in pixels, or 0 for none. A {@link RecyclerView} inside
	 *                   the menu's own wrap-content container (see OverlayMenuView.MenuBuilder#init)
	 *                   has nothing else forcing it to a sensible width -- a preference row's own
	 *                   match-parent width just resolves against the RecyclerView's, which resolves
	 *                   against nothing, collapsing the whole popup (and anything meant to stretch
	 *                   inside a row, like a slider) down toward zero. The boolean overload's
	 *                   2/3-screen-width default suits a multi-item list menu (sort/view); a single
	 *                   compact row (e.g. one slider) still needs *some* explicit minimum, just a
	 *                   smaller one, rather than none at all.
	 */
	public void addToMenu(OverlayMenu.Builder b, int minWidthPx) {
		addToMenu(b, minWidthPx, true);
	}

	/**
	 * @param requestFocus whether to focus the first row right away. Worth skipping for a
	 *                      single-row popup with nothing to navigate between: {@code PreferenceView}
	 *                      is built on the platform's own {@code android.R.attr.preferenceStyle},
	 *                      whose default background paints a solid focused-state highlight -- fine
	 *                      (even necessary) for D-pad/keyboard navigation across a list of rows, but
	 *                      on a lone row it shows up as a second, inner colored box nested inside
	 *                      this menu's own rounded background the moment the popup opens.
	 */
	public void addToMenu(OverlayMenu.Builder b, int minWidthPx, boolean requestFocus) {
		RecyclerView v = createView(b.getMenu().getContext(), minWidthPx);
		b.setCloseHandlerHandler(m -> ((PreferenceViewAdapter) v.getAdapter()).onDestroy());
		b.setView(v);
		if (requestFocus) v.requestFocus();
	}

	public RecyclerView createView(Context ctx, boolean setMinWidth) {
		return createView(ctx,
				setMinWidth ? (Resources.getSystem().getDisplayMetrics().widthPixels * 2 / 3) : 0);
	}

	public RecyclerView createView(Context ctx, int minWidthPx) {
		RecyclerView v = new RecyclerView(ctx) {
			@Override
			public View focusSearch(View focused, int direction) {
				View found = super.focusSearch(focused, direction);
				View v = found;
				for (ViewParent p = (v == null) ? null : v.getParent(); p != null; p = p.getParent()) {
					if (p == this) return v;
				}
				return found;
			}
		};
		addToView(v);
		if (minWidthPx > 0) v.setMinimumWidth(minWidthPx);
		return v;
	}

	public void configure(Consumer<PreferenceSet> c) {
		preferences.clear();
		c.accept(this);
		notifyChanged();
	}

	void setAdapter(PreferenceViewAdapter adapter) {
		this.adapter = adapter;
	}

	private void add(Supplier<? extends PreferenceView.Opts> supplier) {
		preferences.add(supplier);
	}

	private void notifyChanged() {
		if ((adapter != null) && (adapter.getPreferenceSet() == this)) {
			adapter.setPreferenceSet(this);
		}
	}
}
