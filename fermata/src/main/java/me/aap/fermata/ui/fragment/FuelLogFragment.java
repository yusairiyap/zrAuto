package me.aap.fermata.ui.fragment;

import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.DateUtils.formatDateRange;
import static android.text.format.DateUtils.formatDateTime;
import static android.text.format.DateUtils.isToday;

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
 * The Fuel Log tab: an overview card (current trip distance, last refuel summary) and a
 * date-filtered "Timeline" of Trip Started/Trip Ended/Refuel events, grouped by day and drawn as a
 * vertical road with popup-style cards -- all on one scrolling screen. (There used to be a
 * separate "History" list below the Timeline too, but it showed the same entries a second time
 * with no filtering, so it was dropped rather than kept in sync with the Timeline.) All of it
 * lives in a single {@link RecyclerView} (built from a flat {@link Row} list re-derived on every
 * refresh) rather than a header view (or a nested Timeline scroller) wrapped in a separate scroll
 * container -- the rest of the app never nests a scrolling view inside the fragment host's own
 * (wrap_content-height) frame, and doing so here made this screen's content render displaced
 * upwards, overlapping the toolbar title above it.
 */
public class FuelLogFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_TIMELINE_HEADER = 1;
	private static final int TYPE_TIMELINE_DAY_HEADER = 2;
	private static final int TYPE_TIMELINE_ITEM = 3;
	private static final int TYPE_TIMELINE_EMPTY = 4;

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

	/** The last 7 calendar days, including today, in local time. */
	private void resetDefaultDateRange() {
		Calendar to = Calendar.getInstance();
		to.set(Calendar.HOUR_OF_DAY, 23);
		to.set(Calendar.MINUTE, 59);
		to.set(Calendar.SECOND, 59);
		to.set(Calendar.MILLISECOND, 999);
		timelineToMillis = to.getTimeInMillis();

		Calendar from = (Calendar) to.clone();
		from.add(Calendar.DAY_OF_YEAR, -6);
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
				// The app's own theme variants remap Material3 color roles in ways that break the
				// calendar's assumed contrast (see AppTheme.MaterialCalendar's own doc) -- pin it to a
				// palette that stays legible regardless of which variant is active.
				.setTheme(R.style.AppTheme_MaterialCalendar)
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

	/** "Today"/"Yesterday" for those two days, otherwise a full weekday + date (e.g. "Thursday, 24
	 * Sept") -- the label for a Timeline day-group header, so each event card underneath only needs
	 * to show its own time rather than repeating the date on every single card. */
	private String formatDayHeader(long timeMillis) {
		if (isToday(timeMillis)) return getString(R.string.fuel_log_today);

		Calendar day = Calendar.getInstance();
		day.setTimeInMillis(timeMillis);
		Calendar yesterday = Calendar.getInstance();
		yesterday.add(Calendar.DAY_OF_YEAR, -1);
		if ((day.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR))
				&& (day.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR))) {
			return getString(R.string.fuel_log_yesterday);
		}

		return formatDateTime(requireContext(), timeMillis,
				FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH | FORMAT_SHOW_WEEKDAY);
	}

	/** True if both times fall on the same local calendar day. */
	private static boolean isSameDay(long a, long b) {
		Calendar ca = Calendar.getInstance();
		ca.setTimeInMillis(a);
		Calendar cb = Calendar.getInstance();
		cb.setTimeInMillis(b);
		return (ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR))
				&& (ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR));
	}

	/** A single row of the flat list backing the Fuel Log's one {@link RecyclerView}. */
	private static final class Row {
		final int type;
		@Nullable
		final FuelLogEntry entry;
		@Nullable
		final String label;

		Row(int type, @Nullable FuelLogEntry entry) {
			this(type, entry, null);
		}

		Row(int type, @Nullable FuelLogEntry entry, @Nullable String label) {
			this.type = type;
			this.entry = entry;
			this.label = label;
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
				// entries (and so timeline, a filtered copy of it) is already sorted newest-first, so
				// consecutive same-day entries are always adjacent -- a single pass inserting a day
				// header on each day boundary is enough, no separate grouping/sorting pass needed.
				long lastDay = Long.MIN_VALUE;
				for (FuelLogEntry e : timeline) {
					if ((lastDay == Long.MIN_VALUE) || !isSameDay(lastDay, e.time)) {
						r.add(new Row(TYPE_TIMELINE_DAY_HEADER, null, formatDayHeader(e.time)));
						lastDay = e.time;
					}
					r.add(new Row(TYPE_TIMELINE_ITEM, e));
				}
			}

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
				case TYPE_TIMELINE_DAY_HEADER:
					return new DayHeaderViewHolder(
							inflater.inflate(R.layout.fuel_log_timeline_day_header, parent, false));
				case TYPE_TIMELINE_EMPTY:
					return new RecyclerView.ViewHolder(
							inflater.inflate(R.layout.fuel_log_timeline_empty, parent, false)) {};
				default:
					return new TimelineItemViewHolder(
							inflater.inflate(R.layout.fuel_log_timeline_item, parent, false));
			}
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			Row row = rows.get(position);
			if (h instanceof HeaderViewHolder header) {
				bindHeader(header);
			} else if (h instanceof TimelineHeaderViewHolder timelineHeader) {
				bindTimelineHeader(timelineHeader);
			} else if (h instanceof DayHeaderViewHolder dayHeader) {
				dayHeader.label.setText(row.label);
			} else if (h instanceof TimelineItemViewHolder timelineItem) {
				bindTimelineItem(timelineItem, row.entry);
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

		private void bindTimelineItem(TimelineItemViewHolder h, FuelLogEntry e) {
			h.time.setText(formatDateTime(requireContext(), e.time, FORMAT_SHOW_TIME));
			h.event.setText(eventLabel(e.type));
			h.distance.setText(formatKm(e.distanceKm));
			h.location.setText(
					e.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) : e.location);

			h.dot.setImageResource(eventIcon(e.type));
			h.dot.setBackgroundResource(eventDotBackground(e.type));
			h.card.setOnClickListener(v -> FuelRefuelDialog.edit(activity, e, FuelLogFragment.this::refresh));
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
		 * backed by an actual {@link FuelLogEntry} (Timeline items) are swipeable -- the overview
		 * card and the section/day headers and empty-state row never are.
		 */
		ItemTouchHelper.Callback getItemTouchCallback() {
			return new ItemTouchHelper.Callback() {
				@Override
				public boolean isLongPressDragEnabled() {
					return false;
				}

				@Override
				public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
					if (vh.getItemViewType() != TYPE_TIMELINE_ITEM) return 0;
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

		final class DayHeaderViewHolder extends RecyclerView.ViewHolder {
			final TextView label;

			DayHeaderViewHolder(@NonNull View v) {
				super(v);
				label = (TextView) v;
			}
		}

		final class TimelineItemViewHolder extends RecyclerView.ViewHolder {
			final View card;
			final ImageView dot;
			final TextView time;
			final TextView event;
			final TextView distance;
			final TextView location;

			TimelineItemViewHolder(@NonNull View v) {
				super(v);
				card = v.findViewById(R.id.fuel_log_timeline_card);
				dot = v.findViewById(R.id.fuel_log_timeline_dot);
				time = v.findViewById(R.id.fuel_log_timeline_time);
				event = v.findViewById(R.id.fuel_log_timeline_event);
				distance = v.findViewById(R.id.fuel_log_timeline_distance);
				location = v.findViewById(R.id.fuel_log_timeline_location);
			}
		}
	}
}
