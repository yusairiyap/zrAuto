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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.fuel.FuelLogEntry;
import me.aap.fermata.addon.fuel.FuelLogStore;
import me.aap.fermata.addon.fuel.FuelRefuelDialog;
import me.aap.fermata.addon.fuel.FuelTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * The Fuel Log tab: an overview card (current trip distance, last refuel summary) and the history
 * of past refuels, both on one scrolling screen. Both live in a single {@link RecyclerView} (the
 * overview card as a header at position 0) rather than a header view wrapped in a separate scroll
 * container -- the rest of the app never nests a scrolling view inside the fragment host's own
 * (wrap_content-height) frame, and doing so here made this screen's content render displaced
 * upwards, overlapping the toolbar title above it.
 */
public class FuelLogFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM = 1;

	private final Runnable trackerListener = this::refresh;
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

	private static String formatKm(double km) {
		return String.format(Locale.getDefault(), "%.1f km", km);
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private final MainActivityDelegate activity;
		private List<FuelLogEntry> entries = new ArrayList<>();

		Adapter(MainActivityDelegate activity) {
			this.activity = activity;
		}

		void setEntries(List<FuelLogEntry> entries) {
			this.entries = entries;
			notifyDataSetChanged();
		}

		@Override
		public int getItemViewType(int position) {
			return (position == 0) ? TYPE_HEADER : TYPE_ITEM;
		}

		@Override
		public int getItemCount() {
			return 1 + entries.size();
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inflater = LayoutInflater.from(parent.getContext());
			if (viewType == TYPE_HEADER) {
				return new HeaderViewHolder(inflater.inflate(R.layout.fuel_log_header, parent, false));
			}
			return new ItemViewHolder(inflater.inflate(R.layout.fuel_log_item, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			if (h instanceof HeaderViewHolder header) {
				bindHeader(header);
			} else if (h instanceof ItemViewHolder item) {
				bindItem(item, entries.get(position - 1));
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

			h.empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
			h.refuelButton.setOnClickListener(v -> FuelRefuelDialog.show(activity, FuelLogFragment.this::refresh));
		}

		private void bindItem(ItemViewHolder h, FuelLogEntry e) {
			h.distance.setText(formatKm(e.distanceKm));
			h.location.setText(
					e.location.isEmpty() ? getString(R.string.fuel_log_unknown_location) : e.location);
			h.date.setText(formatDateTime(requireContext(), e.time,
					FORMAT_SHOW_DATE | FORMAT_SHOW_TIME | FORMAT_SHOW_YEAR));
			h.edit.setOnClickListener(v -> FuelRefuelDialog.edit(activity, e, FuelLogFragment.this::refresh));
		}

		private void removeEntry(int position) {
			int idx = position - 1;
			if ((idx < 0) || (idx >= entries.size())) return;
			FuelLogStore.removeEntry(activity.getPrefs(), entries.get(idx).id);
			entries.remove(idx);
			notifyItemRemoved(position);
			notifyItemChanged(0);
		}

		/**
		 * Overridden outright (rather than via {@code MovableRecyclerViewAdapter}) so the header at
		 * position 0 -- the overview card, not a list entry -- never becomes swipeable/draggable.
		 */
		ItemTouchHelper.Callback getItemTouchCallback() {
			return new ItemTouchHelper.Callback() {
				@Override
				public boolean isLongPressDragEnabled() {
					return false;
				}

				@Override
				public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
					if (vh.getItemViewType() == TYPE_HEADER) return 0;
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
			final TextView empty;
			final MaterialButton refuelButton;

			HeaderViewHolder(@NonNull View v) {
				super(v);
				currentDistance = v.findViewById(R.id.fuel_log_current_distance);
				lastRefuelSummary = v.findViewById(R.id.fuel_log_last_refuel_summary);
				lastRefuelDate = v.findViewById(R.id.fuel_log_last_refuel_date);
				empty = v.findViewById(R.id.fuel_log_empty);
				refuelButton = v.findViewById(R.id.fuel_log_refuel_button);
			}
		}

		final class ItemViewHolder extends RecyclerView.ViewHolder {
			final TextView distance;
			final TextView location;
			final TextView date;
			final ImageView edit;

			ItemViewHolder(@NonNull View v) {
				super(v);
				distance = v.findViewById(R.id.fuel_log_item_distance);
				location = v.findViewById(R.id.fuel_log_item_location);
				date = v.findViewById(R.id.fuel_log_item_date);
				edit = v.findViewById(R.id.fuel_log_item_edit);
			}
		}
	}
}
