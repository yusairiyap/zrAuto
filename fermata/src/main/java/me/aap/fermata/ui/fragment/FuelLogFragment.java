package me.aap.fermata.ui.fragment;

import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.DateUtils.formatDateTime;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.fuel.FuelLogEntry;
import me.aap.fermata.addon.fuel.FuelLogStore;
import me.aap.fermata.addon.fuel.FuelRefuelDialog;
import me.aap.fermata.addon.fuel.FuelTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.view.MovableRecyclerViewAdapter;

/**
 * The Fuel Log tab: an overview card (current trip distance, last refuel summary) and the history
 * of past refuels, both on one scrolling screen.
 */
public class FuelLogFragment extends MainActivityFragment {
	private final Runnable trackerListener = this::refresh;
	private TextView currentDistance;
	private TextView lastRefuelSummary;
	private TextView lastRefuelDate;
	private TextView empty;
	private Adapter adapter;

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
		currentDistance = view.findViewById(R.id.fuel_log_current_distance);
		lastRefuelSummary = view.findViewById(R.id.fuel_log_last_refuel_summary);
		lastRefuelDate = view.findViewById(R.id.fuel_log_last_refuel_date);
		empty = view.findViewById(R.id.fuel_log_empty);

		MainActivityDelegate a = getActivityDelegate();
		MaterialButton refuel = view.findViewById(R.id.fuel_log_refuel_button);
		refuel.setOnClickListener(v -> FuelRefuelDialog.show(a, this::refresh));

		RecyclerView list = view.findViewById(R.id.fuel_log_list);
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		adapter = new Adapter(a);
		list.setAdapter(adapter);
		new ItemTouchHelper(adapter.getItemTouchCallback()).attachToRecyclerView(list);

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
		if ((currentDistance == null) || !isAdded()) return;
		MainActivityDelegate a = getActivityDelegate();
		double distanceKm = FuelLogStore.getTripDistanceKm(a.getPrefs());
		currentDistance.setText(formatKm(distanceKm));

		FuelLogEntry last = FuelLogStore.getLastEntry(a.getPrefs());
		if (last == null) {
			lastRefuelSummary.setText(R.string.fuel_log_no_entries);
			lastRefuelDate.setText("");
		} else {
			lastRefuelSummary.setText(getString(R.string.fuel_log_summary_format, formatKm(last.distanceKm),
					last.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) : last.location));
			lastRefuelDate.setText(formatDateTime(requireContext(), last.time,
					FORMAT_SHOW_DATE | FORMAT_SHOW_TIME | FORMAT_SHOW_YEAR));
		}

		adapter.setEntries(FuelLogStore.getEntries(a.getPrefs()));
		empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
	}

	private static String formatKm(double km) {
		return String.format(Locale.getDefault(), "%.1f km", km);
	}

	private final class Adapter extends MovableRecyclerViewAdapter<Adapter.ViewHolder> {
		private final MainActivityDelegate activity;
		private List<FuelLogEntry> entries = List.of();

		Adapter(MainActivityDelegate activity) {
			this.activity = activity;
		}

		void setEntries(List<FuelLogEntry> entries) {
			this.entries = entries;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.fuel_log_item, parent, false);
			return new ViewHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder h, int position) {
			FuelLogEntry e = entries.get(position);
			h.distance.setText(formatKm(e.distanceKm));
			h.location.setText(
					e.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) : e.location);
			h.date.setText(formatDateTime(requireContext(), e.time,
					FORMAT_SHOW_DATE | FORMAT_SHOW_TIME | FORMAT_SHOW_YEAR));
			h.edit.setOnClickListener(v -> FuelRefuelDialog.edit(activity, e, FuelLogFragment.this::refresh));
		}

		@Override
		public int getItemCount() {
			return entries.size();
		}

		@Override
		protected void onItemDismiss(int position) {
			if ((position < 0) || (position >= entries.size())) return;
			FuelLogStore.removeEntry(activity.getPrefs(), entries.get(position).id);
			entries.remove(position);
			if (entries.isEmpty()) empty.setVisibility(View.VISIBLE);
		}

		@Override
		protected boolean onItemMove(int fromPosition, int toPosition) {
			// Entries are always shown newest-first, sorted by their own date/time -- reordering by
			// drag has no separate meaning to persist.
			return false;
		}

		@Override
		protected boolean isLongPressDragEnabled() {
			return false;
		}

		final class ViewHolder extends RecyclerView.ViewHolder {
			final TextView distance;
			final TextView location;
			final TextView date;
			final ImageView edit;

			ViewHolder(@NonNull View v) {
				super(v);
				distance = v.findViewById(R.id.fuel_log_item_distance);
				location = v.findViewById(R.id.fuel_log_item_location);
				date = v.findViewById(R.id.fuel_log_item_date);
				edit = v.findViewById(R.id.fuel_log_item_edit);
			}
		}
	}
}
