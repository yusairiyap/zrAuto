package me.aap.fermata.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * Reads back what {@link DiagnosticLog} recorded, so a trace captured in the car can be reviewed,
 * copied or shared without adb. Reachable from Settings &gt; Other &gt; Diagnostics; not a nav-bar
 * tab, since it only exists while someone is actively chasing a bug.
 *
 * @see DiagnosticLog
 */
public class DiagnosticLogFragment extends MainActivityFragment {
	/**
	 * A share intent's payload crosses a Binder transaction, which is hard-capped around 1MB for
	 * the whole transaction -- exceeding it throws rather than truncating. The log's own entry cap
	 * keeps it well under this in practice; this is the backstop for the pathological case, and
	 * keeps the newest (most relevant) end.
	 */
	private static final int MAX_SHARE_CHARS = 200_000;
	@Nullable
	private TextView text;

	@Override
	public int getFragmentId() {
		return R.id.diagnostic_log_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getResources().getString(R.string.diagnostic_log);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.diagnostic_log, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
		super.onViewCreated(view, state);
		text = view.findViewById(R.id.diagnostic_log_text);
		// The whole root, not just the ScrollView: the action row above it would otherwise sit
		// under the translucent toolbar. See the layout's own comment.
		if (view instanceof ViewGroup vg) {
			MainActivityDelegate.getActivityDelegate(view.getContext())
					.onSuccess(a -> a.insetScrollableContent(vg));
		}
		view.findViewById(R.id.diagnostic_log_copy).setOnClickListener(v -> copy());
		view.findViewById(R.id.diagnostic_log_share).setOnClickListener(v -> share());
		view.findViewById(R.id.diagnostic_log_refresh).setOnClickListener(v -> refresh(true));
		view.findViewById(R.id.diagnostic_log_clear).setOnClickListener(v -> {
			DiagnosticLog.clear();
			refresh(false);
		});
		refresh(true);
	}

	@Override
	public void onResume() {
		super.onResume();
		refresh(true);
	}

	@Override
	public void onDestroyView() {
		text = null;
		super.onDestroyView();
	}

	private void refresh(boolean scrollToEnd) {
		TextView t = text;
		if (t == null) return;
		String dump = DiagnosticLog.dump();
		if (dump.isEmpty()) {
			t.setText(DiagnosticLog.isEnabled() ? R.string.diagnostic_log_empty :
					R.string.diagnostic_log_disabled);
			return;
		}
		t.setText(dump);
		if (!scrollToEnd) return;
		View v = getView();
		if (v == null) return;
		ScrollView sv = v.findViewById(R.id.diagnostic_log_scroll);
		// Newest entries are what anyone opening this wants first, and they're at the bottom.
		if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
	}

	private void copy() {
		String dump = DiagnosticLog.dump();
		Context ctx = getContext();
		if ((ctx == null) || dump.isEmpty()) return;
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null) return;
			cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostic_log), dump));
			UiUtils.showInfo(ctx, R.string.diagnostic_log_copied);
		} catch (Exception err) {
			Log.e(err, "Failed to copy the diagnostic log");
			UiUtils.showAlert(ctx, String.valueOf(err.getLocalizedMessage()));
		}
	}

	private void share() {
		String dump = DiagnosticLog.dump();
		Context ctx = getContext();
		if ((ctx == null) || dump.isEmpty()) return;
		if (dump.length() > MAX_SHARE_CHARS) dump = dump.substring(dump.length() - MAX_SHARE_CHARS);
		try {
			// EXTRA_TEXT rather than a file + FileProvider: the provider is only declared in the Auto
			// build's manifest, and the payload is small enough that a plain text share works from
			// every build and needs no grant plumbing.
			Intent i = new Intent(Intent.ACTION_SEND);
			i.setType("text/plain");
			i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostic_log));
			i.putExtra(Intent.EXTRA_TEXT, dump);
			startActivity(Intent.createChooser(i, getString(R.string.diagnostic_log_share)));
		} catch (Exception err) {
			Log.e(err, "Failed to share the diagnostic log");
			UiUtils.showAlert(ctx, String.valueOf(err.getLocalizedMessage()));
		}
	}
}
