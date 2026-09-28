package me.aap.utils.pref;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import me.aap.utils.R;
import me.aap.utils.app.App;
import me.aap.utils.ui.UiUtils;


/**
 * @author Andrey Pavlenko
 */
public class PreferenceViewAdapter extends RecyclerView.Adapter<PreferenceViewAdapter.PrefViewHolder> {
	private PreferenceSet set;
	private RecyclerView recyclerView;

	public PreferenceViewAdapter(PreferenceSet set) {
		this.set = set;
	}

	@Override
	public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
		this.recyclerView = recyclerView;
	}

	@NonNull
	@Override
	public PrefViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		Context ctx = parent.getContext();
		PreferenceView v = new PreferenceView(ctx);
		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		int marginH = (int) UiUtils.toPx(ctx, 12);
		int marginV = (int) UiUtils.toPx(ctx, 4);
		int paddingH = (int) UiUtils.toPx(ctx, 20);
		int paddingV = (int) UiUtils.toPx(ctx, 14);
		v.setLayoutParams(lp);
		lp.setMargins(marginH, marginV, marginH, marginV);
		v.setPadding(paddingH, paddingV, paddingH, paddingV);
		v.setFocusable(true);
		// Flat rounded cards, no shadow: the same clean look as the Data Usage tab's.
		v.setBackgroundResource(R.drawable.box_secondary);
		return new PrefViewHolder(v);
	}

	@Override
	public void onBindViewHolder(@NonNull PrefViewHolder holder, int position) {
		holder.getPreferenceView().setPreference(this, set.preferences.get(position));
	}

	@Override
	public void onViewRecycled(@NonNull PrefViewHolder holder) {
		holder.getPreferenceView().cleanUp();
		holder.getPreferenceView().setPreference(this, null);
	}

	public void onDestroy() {
		RecyclerView view = recyclerView;
		if (view == null) return;
		for (int i = 0, n = view.getChildCount(); i < n; i++) {
			View v = view.getChildAt(i);
			if (v instanceof PreferenceView) ((PreferenceView) v).cleanUp();
		}
		view.setAdapter(null);
	}

	@Override
	public int getItemCount() {
		return set.preferences.size();
	}

	public void setPreferenceSet(PreferenceSet set) {
		PreferenceSet old = getPreferenceSet();
		if (old != null) old.setAdapter(null);

		this.set = set;
		set.setAdapter(this);

		// Fades the new page in instead of the instant content swap RecyclerView otherwise shows.
		// Resetting alpha to 0 before notifyDataSetChanged() means the outgoing rows are never drawn
		// mid-fade -- they're simply replaced within the same frame, and only the new page animates.
		if ((recyclerView != null) && (old != set)) {
			recyclerView.animate().cancel();
			recyclerView.setAlpha(0f);
			recyclerView.animate().alpha(1f).setDuration(200).start();
		}

		notifyDataSetChanged();

		if ((old != null) && (recyclerView != null)) {
			int idx = set.getPreferences().indexOf(old);

			if (idx != -1) {
				App.get().getHandler().post(() -> {
					if (set != this.set) return;
					PrefViewHolder h = (PrefViewHolder) recyclerView.findViewHolderForAdapterPosition(idx);
					if (h == null) return;
					h.getPreferenceView().requestFocus();
					recyclerView.smoothScrollToPosition(idx);
				});
			}
		}
	}

	public PreferenceSet getPreferenceSet() {
		return set;
	}

	public void setSize(float scale) {
		if (recyclerView == null) return;
		Context ctx = recyclerView.getContext();
		for (int i = 0, n = recyclerView.getChildCount(); i < n; i++) {
			((PreferenceView) recyclerView.getChildAt(i)).setSize(ctx, scale);
		}
	}

	public static class PrefViewHolder extends RecyclerView.ViewHolder {

		public PrefViewHolder(@NonNull PreferenceView pref) {
			super(pref);
		}

		public PreferenceView getPreferenceView() {
			return (PreferenceView) itemView;
		}
	}
}
