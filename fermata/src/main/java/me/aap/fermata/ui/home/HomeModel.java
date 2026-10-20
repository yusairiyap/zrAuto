package me.aap.fermata.ui.home;

import me.aap.fermata.ui.home.widget.HomeWidgets;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * What the Home tab shows: an ordered list of cells, each one a stack of one or more widgets of
 * the same size (w x h grid units). The order is all there is to the arrangement: the grid packs
 * the cells in that order, so the same layout fits a tall phone, a wide tablet and the car screen.
 * Kept as JSON in {@link MainActivityPrefs#HOME_LAYOUT}.
 */
public final class HomeModel {
	/** One widget: its type, a key of its own and the options the user set on it. */
	public static final class Item {
		public final String id;
		public final String type;
		public final JSONObject opts;

		Item(String id, String type, JSONObject opts) {
			this.id = id;
			this.type = type;
			this.opts = opts;
		}
	}

	/** One place in the grid: the widgets stacked there, and which one is on top. */
	public static final class Cell {
		public final long id;
		public int w;
		public int h;
		public int top;
		public final List<Item> items = new ArrayList<>();

		Cell(long id, int w, int h) {
			this.id = id;
			this.w = w;
			this.h = h;
		}

		public Item topItem() {
			return items.get(Math.max(0, Math.min(top, items.size() - 1)));
		}
	}

	/** The widgets of a layout nobody has changed yet. */
	private static final String[] DEFAULT_TYPES = {"media", "pages", "favorites", "playlists", "fuel",
			"data", "power", "ambience"};
	private static final long SAVE_DELAY_MS = 400;

	private final MainActivityPrefs prefs;
	private final List<Cell> cells = new ArrayList<>();
	private long nextCell = 1;
	private long nextItem = 1;
	/** What was last written, to tell our own saves from a reset made elsewhere. */
	@Nullable
	private String saved;
	private final Runnable saveNow = this::save;

	public HomeModel(MainActivityPrefs prefs) {
		this.prefs = prefs;
		load();
	}

	public List<Cell> cells() {
		return cells;
	}

	@Nullable
	public Cell find(long id) {
		for (Cell c : cells) if (c.id == id) return c;
		return null;
	}

	/** Reads the layout again; true if it was not what this model last wrote (a reset, a restore). */
	public boolean reloadIfChanged() {
		String now = prefs.getStringPref(MainActivityPrefs.HOME_LAYOUT);
		if ((now == null) ? (saved == null) : now.equals(saved)) return false;
		load();
		return true;
	}

	private void load() {
		cells.clear();
		String json = prefs.getStringPref(MainActivityPrefs.HOME_LAYOUT);
		saved = json;
		if (json == null) {
			defaults();
			return;
		}
		try {
			JSONObject root = new JSONObject(json);
			JSONArray arr = root.getJSONArray("cells");
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.getJSONObject(i);
				Cell c = new Cell(o.getLong("id"), o.getInt("w"), o.getInt("h"));
				c.top = o.optInt("top", 0);
				JSONArray items = o.getJSONArray("items");
				for (int j = 0; j < items.length(); j++) {
					JSONObject io = items.getJSONObject(j);
					String type = io.getString("type");
					if (HomeWidgets.find(type) == null) continue; // A widget this build doesn't have.
					JSONObject opts = io.optJSONObject("o");
					c.items.add(new Item(io.getString("id"), type, (opts != null) ? opts : new JSONObject()));
				}
				if (c.items.isEmpty()) continue;
				c.top = Math.max(0, Math.min(c.top, c.items.size() - 1));
				cells.add(c);
				nextCell = Math.max(nextCell, c.id + 1);
			}
		} catch (JSONException ex) {
			Log.e(ex, "Failed to read the Home layout");
			cells.clear();
			defaults();
		}
	}

	private void defaults() {
		cells.clear();
		for (String key : DEFAULT_TYPES) {
			HomeWidgetType t = HomeWidgets.find(key);
			if (t != null) add(t);
		}
	}

	/** Every widget removed, the way a fresh install has it. */
	public void reset() {
		prefs.removePref(MainActivityPrefs.HOME_LAYOUT);
		load();
	}

	/** A new cell at the end, in the widget's default size. */
	public Cell add(HomeWidgetType t) {
		Cell c = new Cell(nextCell++, t.defW, t.defH);
		c.items.add(newItem(t));
		cells.add(c);
		return c;
	}

	private Item newItem(HomeWidgetType t) {
		return new Item("i" + Long.toString(System.currentTimeMillis(), 36) + (nextItem++), t.key,
				new JSONObject());
	}

	/** Takes the widget out; true if that emptied (and so removed) its cell. */
	public boolean remove(Cell c, Item it) {
		c.items.remove(it);
		if (c.items.isEmpty()) {
			cells.remove(c);
			return true;
		}
		c.top = Math.max(0, Math.min(c.top, c.items.size() - 1));
		return false;
	}

	/** Puts back what {@link #remove} took out (the user tapped Undo). */
	public void restore(Cell c, Item it, int cellIndex, int itemIndex, boolean cellWasRemoved) {
		if (cellWasRemoved) {
			c.items.clear();
			c.items.add(it);
			c.top = 0;
			cells.add(Math.max(0, Math.min(cellIndex, cells.size())), c);
		} else {
			c.items.add(Math.max(0, Math.min(itemIndex, c.items.size())), it);
			c.top = Math.max(0, Math.min(itemIndex, c.items.size() - 1));
		}
	}

	/** Puts the cells in the order given (the same cells). */
	public void setOrder(List<Cell> order) {
		if (order.size() != cells.size()) return;
		cells.clear();
		cells.addAll(order);
	}

	/** Moves everything stacked in {@code src} onto {@code target}'s stack, and drops {@code src}. */
	public void stack(Cell target, Cell src) {
		target.items.addAll(src.items);
		target.top = target.items.size() - 1;
		cells.remove(src);
	}

	/** Takes {@code it} out of its stack into a cell of its own right after it. */
	public Cell unstack(Cell c, Item it) {
		int at = c.items.indexOf(it);
		if ((at < 0) || (c.items.size() < 2)) return c;
		c.items.remove(it);
		c.top = Math.max(0, Math.min(c.top, c.items.size() - 1));
		Cell n = new Cell(nextCell++, c.w, c.h);
		n.items.add(it);
		cells.add(cells.indexOf(c) + 1, n);
		return n;
	}

	/** Whether any widget has a look of its own, other than following Home's. */
	public boolean anyStyleOverride() {
		for (Cell c : cells) {
			for (Item it : c.items) {
				if (it.opts.optInt(HomeWidgetView.OPT_STYLE, HomeWidgetView.STYLE_AUTO) != HomeWidgetView.STYLE_AUTO) {
					return true;
				}
			}
		}
		return false;
	}

	/** Every widget goes back to following Home's look. */
	public void clearStyles() {
		for (Cell c : cells) for (Item it : c.items) it.opts.remove(HomeWidgetView.OPT_STYLE);
	}

	/** Changes one option of a widget (null removes it). */
	public void setOpt(Item it, String key, @Nullable Object value) {
		try {
			if (value == null) it.opts.remove(key);
			else it.opts.put(key, value);
		} catch (JSONException ex) {
			Log.e(ex, "Failed to set a widget option");
		}
	}

	/** Writes the layout soon: a drag or a slider makes many changes in a row. */
	public void saveLater() {
		App.get().getHandler().removeCallbacks(saveNow);
		App.get().getHandler().postDelayed(saveNow, SAVE_DELAY_MS);
	}

	public void save() {
		App.get().getHandler().removeCallbacks(saveNow);
		try {
			JSONArray arr = new JSONArray();
			for (Cell c : cells) {
				JSONObject o = new JSONObject();
				o.put("id", c.id);
				o.put("w", c.w);
				o.put("h", c.h);
				o.put("top", c.top);
				JSONArray items = new JSONArray();
				for (Item it : c.items) {
					JSONObject io = new JSONObject();
					io.put("id", it.id);
					io.put("type", it.type);
					io.put("o", it.opts);
					items.put(io);
				}
				o.put("items", items);
				arr.put(o);
			}
			JSONObject root = new JSONObject();
			root.put("v", 1);
			root.put("cells", arr);
			saved = root.toString();
			prefs.applyStringPref(MainActivityPrefs.HOME_LAYOUT, saved);
		} catch (JSONException ex) {
			Log.e(ex, "Failed to save the Home layout");
		}
	}
}
