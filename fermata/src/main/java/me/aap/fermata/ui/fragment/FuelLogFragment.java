package me.aap.fermata.ui.fragment;

import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.DateUtils.formatDateRange;
import static android.text.format.DateUtils.formatDateTime;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import me.aap.fermata.R;
import me.aap.fermata.addon.fuel.FuelLogEntry;
import me.aap.fermata.addon.fuel.FuelLogStore;
import me.aap.fermata.addon.fuel.FuelRefuelDialog;
import me.aap.fermata.addon.fuel.FuelTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * The Fuel Log tab: an overview card (current trip distance, last refuel summary), a date-filtered
 * "Timeline" of Trip Started/Trip Ended/Refuel events drawn as a vertical road with popup-style
 * cards, and the full "History" list below it -- all on one scrolling screen. All of it lives in a
 * single {@link RecyclerView} (built from a flat {@link Row} list re-derived on every refresh)
 * rather than a header view (or a nested Timeline scroller) wrapped in a separate scroll container
 * -- the rest of the app never nests a scrolling view inside the fragment host's own
 * (wrap_content-height) frame, and doing so here made this screen's content render displaced
 * upwards, overlapping the toolbar title above it.
 */
public class FuelLogFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_TIMELINE_HEADER = 1;
	private static final int TYPE_TIMELINE_ITEM = 2;
	private static final int TYPE_TIMELINE_EMPTY = 3;
	private static final int TYPE_HISTORY_HEADER = 4;
	private static final int TYPE_HISTORY_ITEM = 5;

	private final Runnable trackerListener = this::refresh;
	private Adapter adapter;
	/** Inclusive range, in local wall-clock millis, that the Timeline section is filtered to. */
	private long timelineFromMillis;
	private long timelineToMillis;

	@Override
	public int getFragmentId() {
		return R.id.fuel_log_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.fuel_log_title);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
														@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fuel_log_fragment, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		resetDefaultDateRange();
		MainActivityDelegate a = getActivityDelegate();
		RecyclerView list = (RecyclerView) view;
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		adapter = new Adapter(a);
		list.setAdapter(adapter);
		new ItemTouchHelper(adapter.getItemTouchCallback()).attachToRecyclerView(list);
		// Without this, the list renders flush under the toolbar's translucent title bar instead of
		// reserving space below it -- every other scrollable tab (Settings, Audio Effects, folder
		// browsing) calls this same hook (see MainActivityDelegate.insetScrollableContent) for exactly
		// this reason.
		a.insetScrollableContent(list);
		// Otherwise the RecyclerView clips each card's drop shadow to its own bounds, most visibly
		// cutting the header card's shadow off flush against the list's top edge.
		list.setClipChildren(false);

		FuelTracker.get(requireContext()).start(a);
	}

	@Override
	public void onResume() {
		super.onResume();
		FuelTracker.get(requireContext()).addListener(trackerListener);
		refresh();
	}

	@Override
	public void onPause() {
		super.onPause();
		FuelTracker.get(requireContext()).removeListener(trackerListener);
	}

	private void refresh() {
		if ((adapter == null) || !isAdded()) return;
		adapter.setEntries(FuelLogStore.getEntries(getActivityDelegate().getPrefs()));
	}

	/** Last 1 calendar month up to and including today, in local time. */
	private void resetDefaultDateRange() {
		Calendar to = Calendar.getInstance();
		to.set(Calendar.HOUR_OF_DAY, 23);
		to.set(Calendar.MINUTE, 59);
		to.set(Calendar.SECOND, 59);
		to.set(Calendar.MILLISECOND, 999);
		timelineToMillis = to.getTimeInMillis();

		Calendar from = (Calendar) to.clone();
		from.add(Calendar.MONTH, -1);
		from.set(Calendar.HOUR_OF_DAY, 0);
		from.set(Calendar.MINUTE, 0);
		from.set(Calendar.SECOND, 0);
		from.set(Calendar.MILLISECOND, 0);
		timelineFromMillis = from.getTimeInMillis();
	}

	private void showDateRangePicker() {
		MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
				.setTitleText(R.string.fuel_log_filter_date_range)
				.setSelection(new Pair<>(localMillisToUtcDayMillis(timelineFromMillis),
						localMillisToUtcDayMillis(timelineToMillis)))
				.build();
		picker.addOnPositiveButtonClickListener(sel -> {
			if ((sel == null) || (sel.first == null) || (sel.second == null)) return;
			timelineFromMillis = utcDayMillisToLocalStartOfDay(sel.first);
			timelineToMillis = utcDayMillisToLocalEndOfDay(sel.second);
			refresh();
		});
		picker.show(getParentFragmentManager(), "fuel_log_date_range");
	}

	/**
	 * {@link MaterialDatePicker} works in UTC calendar days -- these convert between that and this
	 * screen's local wall-clock day boundaries so a date picked as (e.g.) "17 Sep" always means
	 * 17 Sep in the device's own timezone, not whatever day that UTC instant happens to fall on
	 * locally.
	 */
	private static long localMillisToUtcDayMillis(long localMillis) {
		Calendar local = Calendar.getInstance();
		local.setTimeInMillis(localMillis);
		Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		utc.clear();
		utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
		return utc.getTimeInMillis();
	}

	private static long utcDayMillisToLocalStartOfDay(long utcMillis) {
		Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		utc.setTimeInMillis(utcMillis);
		Calendar local = Calendar.getInstance();
		local.clear();
		local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
				0, 0, 0);
		return local.getTimeInMillis();
	}

	private static long utcDayMillisToLocalEndOfDay(long utcMillis) {
		Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		utc.setTimeInMillis(utcMillis);
		Calendar local = Calendar.getInstance();
		local.clear();
		local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
				23, 59, 59);
		local.set(Calendar.MILLISECOND, 999);
		return local.getTimeInMillis();
	}

	private static String formatKm(double km) {
		return String.format(Locale.getDefault(), "%.1f km", km);
	}

	/** A single row of the flat list backing the Fuel Log's one {@link RecyclerView}. */
	private static final class Row {
		final int type;
		@Nullable
		final FuelLogEntry entry;

		Row(int type, @Nullable FuelLogEntry entry) {
			this.type = type;
			this.entry = entry;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private final MainActivityDelegate activity;
		private List<FuelLogEntry> entries = new ArrayList<>();
		private List<Row> rows = new ArrayList<>();

		Adapter(MainActivityDelegate activity) {
			this.activity = activity;
		}

		void setEntries(List<FuelLogEntry> entries) {
			this.entries = entries;
			rebuildRows();
		}

		private void rebuildRows() {
			List<Row> r = new ArrayList<>();
			r.add(new Row(TYPE_HEADER, null));
			r.add(new Row(TYPE_TIMELINE_HEADER, null));

			List<FuelLogEntry> timeline = new ArrayList<>();
			for (FuelLogEntry e : entries) {
				if ((e.time >= timelineFromMillis) && (e.time <= timelineToMillis)) timeline.add(e);
			}
			if (timeline.isEmpty()) {
				r.add(new Row(TYPE_TIMELINE_EMPTY, null));
			} else {
				for (FuelLogEntry e : timeline) r.add(new Row(TYPE_TIMELINE_ITEM, e));
			}

			r.add(new Row(TYPE_HISTORY_HEADER, null));
			for (FuelLogEntry e : entries) r.add(new Row(TYPE_HISTORY_ITEM, e));

			rows = r;
			notifyDataSetChanged();
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).type;
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inflater = LayoutInflater.from(parent.getContext());
			switch (viewType) {
				case TYPE_HEADER:
					return new HeaderViewHolder(inflater.inflate(R.layout.fuel_log_header, parent, false));
				case TYPE_TIMELINE_HEADER:
					return new TimelineHeaderViewHolder(
							inflater.inflate(R.layout.fuel_log_timeline_header, parent, false));
				case TYPE_TIMELINE_ITEM:
					return new TimelineItemViewHolder(
							inflater.inflate(R.layout.fuel_log_timeline_item, parent, false));
				case TYPE_TIMELINE_EMPTY:
					return new RecyclerView.ViewHolder(
							inflater.inflate(R.layout.fuel_log_timeline_empty, parent, false)) {};
				case TYPE_HISTORY_HEADER:
					return new HistoryHeaderViewHolder(
							inflater.inflate(R.layout.fuel_log_history_header, parent, false));
				default:
					return new ItemViewHolder(inflater.inflate(R.layout.fuel_log_item, parent, false));
			}
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			Row row = rows.get(position);
			if (h instanceof HeaderViewHolder header) {
				bindHeader(header);
			} else if (h instanceof TimelineHeaderViewHolder timelineHeader) {
				bindTimelineHeader(timelineHeader);
			} else if (h instanceof TimelineItemViewHolder timelineItem) {
				bindTimelineItem(timelineItem, row.entry);
			} else if (h instanceof HistoryHeaderViewHolder historyHeader) {
				bindHistoryHeader(historyHeader);
			} else if (h instanceof ItemViewHolder item) {
				bindItem(item, row.entry);
			}
		}

		private void bindHeader(HeaderViewHolder h) {
			double distanceKm = FuelLogStore.getTripDistanceKm(activity.getPrefs());
			h.currentDistance.setText(formatKm(distanceKm));

			FuelLogEntry last = FuelLogStore.getLastEntry(activity.getPrefs());
			if (last == null) {
				h.lastRefuelSummary.setText(R.string.fuel_log_no_entries);
				h.lastRefuelDate.setText("");
			} else {
				h.lastRefuelSummary.setText(getString(R.string.fuel_log_summary_format,
						formatKm(last.distanceKm),
						last.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) :
								last.location));
				h.lastRefuelDate.setText(formatDateTime(requireContext(), last.time,
						FORMAT_SHOW_DATE | FORMAT_SHOW_TIME | FORMAT_SHOW_YEAR));
			}

			h.refuelButton.setOnClickListener(v -> FuelRefuelDialog.show(activity, FuelLogFragment.this::refresh));
		}

		private void bindTimelineHeader(TimelineHeaderViewHolder h) {
			h.dateRange.setText(formatDateRange(requireContext(), timelineFromMillis, timelineToMillis,
					FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH | FORMAT_SHOW_YEAR));
			h.dateRange.setOnClickListener(v -> showDateRangePicker());
		}

		private void bindHistoryHeader(HistoryHeaderViewHolder h) {
			h.empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
		}

		private void bindTimelineItem(TimelineItemViewHolder h, FuelLogEntry e) {
			h.dayDate.setText(formatDateTime(requireContext(), e.time,
					FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH | FORMAT_SHOW_WEEKDAY));
			h.time.setText(formatDateTime(requireContext(), e.time, FORMAT_SHOW_TIME));
			h.event.setText(eventLabel(e.type));
			h.distance.setText(formatKm(e.distanceKm));
			h.location.setText(
					e.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) : e.location);

			h.dot.setImageResource(eventIcon(e.type));
			h.dot.setBackgroundResource(eventDotBackground(e.type));
			h.card.setOnClickListener(v -> FuelRefuelDialog.edit(activity, e, FuelLogFragment.this::refresh));
		}

		private void bindItem(ItemViewHolder h, FuelLogEntry e) {
			h.icon.setImageResource(eventIcon(e.type));
			if (e.type == FuelLogEntry.Type.REFUEL) {
				h.event.setVisibility(View.GONE);
			} else {
				h.event.setVisibility(View.VISIBLE);
				h.event.setText(eventLabel(e.type));
			}
			h.distance.setText(formatKm(e.distanceKm));
			h.location.setText(
					e.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) : e.location);
			h.date.setText(formatDateTime(requireContext(), e.time,
					FORMAT_SHOW_DATE | FORMAT_SHOW_TIME | FORMAT_SHOW_YEAR));
			View.OnClickListener edit = v -> FuelRefuelDialog.edit(activity, e, FuelLogFragment.this::refresh);
			h.edit.setOnClickListener(edit);
			h.itemView.setOnClickListener(edit);
		}

		private String eventLabel(FuelLogEntry.Type type) {
			return switch (type) {
				case TRIP_START -> getString(R.string.fuel_log_trip_started);
				case TRIP_END -> getString(R.string.fuel_log_trip_ended);
				case REFUEL -> getString(R.string.fuel_log_refuel);
			};
		}

		private int eventIcon(FuelLogEntry.Type type) {
			return switch (type) {
				case TRIP_START -> R.drawable.play;
				case TRIP_END -> R.drawable.stop;
				case REFUEL -> R.drawable.fuel;
			};
		}

		/**
		 * A separate drawable per event type (colorControlActivated/colorError/colorOnSecondary,
		 * respectively -- see each drawable's own doc) rather than one shape re-tinted from Java via
		 * {@code MaterialColors.getColor(view, attr)}: {@code colorControlActivated} and {@code
		 * colorError} aren't declared in {@code com.google.android.material}'s own R (confirmed by a
		 * CI compile failure -- "cannot find symbol"), and this project's non-transitive R classes
		 * mean guessing the right androidx artifact for each one in Java is fragile. Letting AAPT
		 * resolve the theme attr while linking the drawable XML sidesteps that entirely.
		 */
		private int eventDotBackground(FuelLogEntry.Type type) {
			return switch (type) {
				case TRIP_START -> R.drawable.timeline_dot_bg_trip_start;
				case TRIP_END -> R.drawable.timeline_dot_bg_trip_end;
				case REFUEL -> R.drawable.timeline_dot_bg_refuel;
			};
		}

		private void removeEntry(int position) {
			Row row = rows.get(position);
			if (row.entry == null) return;
			FuelLogStore.removeEntry(activity.getPrefs(), row.entry.id);
			entries.remove(row.entry);
			rebuildRows();
		}

		/**
		 * Overridden outright (rather than via {@code MovableRecyclerViewAdapter}) so only rows
		 * backed by an actual {@link FuelLogEntry} (Timeline/History items) are swipeable -- the
		 * overview card and the section headers/empty-state rows never are.
		 */
		ItemTouchHelper.Callback getItemTouchCallback() {
			return new ItemTouchHelper.Callback() {
				@Override
				public boolean isLongPressDragEnabled() {
					return false;
				}

				@Override
				public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
					int type = vh.getItemViewType();
					if ((type != TYPE_TIMELINE_ITEM) && (type != TYPE_HISTORY_ITEM)) return 0;
					return makeMovementFlags(0, ItemTouchHelper.START | ItemTouchHelper.END);
				}

				@Override
				public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
															 @NonNull RecyclerView.ViewHolder target) {
					return false;
				}

				@Override
				public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
					removeEntry(vh.getBindingAdapterPosition());
				}
			};
		}

		final class HeaderViewHolder extends RecyclerView.ViewHolder {
			final TextView currentDistance;
			final TextView lastRefuelSummary;
			final TextView lastRefuelDate;
			final MaterialButton refuelButton;

			HeaderViewHolder(@NonNull View v) {
				super(v);
				currentDistance = v.findViewById(R.id.fuel_log_current_distance);
				lastRefuelSummary = v.findViewById(R.id.fuel_log_last_refuel_summary);
				lastRefuelDate = v.findViewById(R.id.fuel_log_last_refuel_date);
				refuelButton = v.findViewById(R.id.fuel_log_refuel_button);
			}
		}

		final class TimelineHeaderViewHolder extends RecyclerView.ViewHolder {
			final MaterialButton dateRange;

			TimelineHeaderViewHolder(@NonNull View v) {
				super(v);
				dateRange = v.findViewById(R.id.fuel_log_timeline_date_range);
			}
		}

		final class TimelineItemViewHolder extends RecyclerView.ViewHolder {
			final View card;
			final ImageView dot;
			final TextView dayDate;
			final TextView time;
			final TextView event;
			final TextView distance;
			final TextView location;

			TimelineItemViewHolder(@NonNull View v) {
				super(v);
				card = v.findViewById(R.id.fuel_log_timeline_card);
				dot = v.findViewById(R.id.fuel_log_timeline_dot);
				dayDate = v.findViewById(R.id.fuel_log_timeline_day_date);
				time = v.findViewById(R.id.fuel_log_timeline_time);
				event = v.findViewById(R.id.fuel_log_timeline_event);
				distance = v.findViewById(R.id.fuel_log_timeline_distance);
				location = v.findViewById(R.id.fuel_log_timeline_location);
			}
		}

		final class HistoryHeaderViewHolder extends RecyclerView.ViewHolder {
			final TextView empty;

			HistoryHeaderViewHolder(@NonNull View v) {
				super(v);
				empty = v.findViewById(R.id.fuel_log_empty);
			}
		}

		final class ItemViewHolder extends RecyclerView.ViewHolder {
			final ImageView icon;
			final TextView event;
			final TextView distance;
			final TextView location;
			final TextView date;
			final ImageView edit;

			ItemViewHolder(@NonNull View v) {
				super(v);
				icon = v.findViewById(R.id.fuel_log_item_icon);
				event = v.findViewById(R.id.fuel_log_item_event);
				distance = v.findViewById(R.id.fuel_log_item_distance);
				location = v.findViewById(R.id.fuel_log_item_location);
				date = v.findViewById(R.id.fuel_log_item_date);
				edit = v.findViewById(R.id.fuel_log_item_edit);
			}
		}
	}
}
