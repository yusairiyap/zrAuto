package me.aap.fermata.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

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
	@Nullable
	private ViewTreeObserver.OnPreDrawListener preDraw;
	@Nullable
	private ScrollView preDrawView;
	private boolean topShown;

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
		// The ScrollView gets the padding that keeps the first and last line clear of the
		// translucent bars while the log scrolls under them (and lets the bars collapse on scroll).
		// See the layout's own comment.
		ScrollView sv = view.findViewById(R.id.diagnostic_log_scroll);
		FloatingActionButton top = view.findViewById(R.id.diagnostic_log_top);
		// The same soft shadow and no ripple wash as the app's own floating buttons.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			top.setOutlineAmbientShadowColor(Color.BLACK);
			top.setOutlineSpotShadowColor(Color.BLACK);
		}
		top.setRippleColor(ColorStateList.valueOf(Color.TRANSPARENT));
		MainActivityDelegate.getActivityDelegate(view.getContext()).onSuccess(a -> {
			a.insetScrollableContent(sv);
			// Just above the back button, wherever it is now: the bottom bars' room, the button's own
			// margin and size, a gap, and whatever it's moved by (it follows the bars as they slide
			// away). By translation, so nothing is relaid out; only touched when the value changes.
			float lift = UiUtils.toPx(a.getContext(), 15 + 56 + 12);
			preDraw = () -> {
				float dy = -(sv.getPaddingBottom() + lift) + a.getFloatingButton().getTranslationY();
				if (top.getTranslationY() != dy) top.setTranslationY(dy);
				return true;
			};
			sv.getViewTreeObserver().addOnPreDrawListener(preDraw);
			preDrawView = sv;
		});
		top.setOnClickListener(v -> sv.smoothScrollTo(0, 0));
		// Shown only once scrolled away from the newest entries at the top.
		sv.setOnScrollChangeListener((v, x, y, ox, oy) -> {
			boolean away = y > UiUtils.toPx(v.getContext(), 24);
			if (away == topShown) return;
			topShown = away;
			if (away) top.show();
			else top.hide();
		});
		view.findViewById(R.id.diagnostic_log_copy).setOnClickListener(v -> copy());
		view.findViewById(R.id.diagnostic_log_share).setOnClickListener(v -> share());
		view.findViewById(R.id.diagnostic_log_refresh).setOnClickListener(v -> refresh());
		view.findViewById(R.id.diagnostic_log_clear).setOnClickListener(v -> {
			DiagnosticLog.clear();
			refresh();
		});
		refresh();
	}

	@Override
	public void onResume() {
		super.onResume();
		refresh();
	}

	@Override
	public void onDestroyView() {
		if ((preDraw != null) && (preDrawView != null)) {
			ViewTreeObserver o = preDrawView.getViewTreeObserver();
			if (o.isAlive()) o.removeOnPreDrawListener(preDraw);
		}
		preDraw = null;
		preDrawView = null;
		topShown = false;
		text = null;
		super.onDestroyView();
	}

	/**
	 * Shows the log, newest entry first, and always from the top: the newest are what anyone
	 * opening or refreshing this wants, and setting the text of a selectable TextView can
	 * otherwise leave the page scrolled down to wherever its selection ended.
	 */
	private void refresh() {
		TextView t = text;
		if (t == null) return;
		String dump = DiagnosticLog.dump();
		if (dump.isEmpty()) {
			t.setText(DiagnosticLog.isEnabled() ? R.string.diagnostic_log_empty :
					R.string.diagnostic_log_disabled);
		} else {
			t.setText(newestFirst(dump));
		}
		View v = getView();
		ScrollView sv = (v == null) ? null : v.findViewById(R.id.diagnostic_log_scroll);
		if (sv == null) return;
		// After the new text is laid out, and once more a moment later: the selectable text can move
		// the scroll position again as it settles.
		sv.post(() -> sv.scrollTo(0, 0));
		sv.postDelayed(() -> sv.scrollTo(0, 0), 100);
	}

	/** {@link DiagnosticLog#dump()} is oldest first; this screen shows the newest on top. */
	private static String newestFirst(String dump) {
		String[] lines = dump.split("\n");
		StringBuilder sb = new StringBuilder(dump.length());
		for (int i = lines.length - 1; i >= 0; i--) sb.append(lines[i]).append('\n');
		return sb.toString();
	}

	private void copy() {
		String dump = DiagnosticLog.dump();
		if (dump.isEmpty()) return;
		// getActivityDelegate().getContext() rather than this Fragment's own getContext(): every
		// other working call site of UiUtils.showInfo()/showAlert() in this app (e.g. SettingsFragment's
		// "Clear browsing data") goes through the delegate's own resolved Context, not the plain
		// Fragment one -- this app's Activity/Fragment glue is custom enough (MainCarActivity isn't
		// even a real Activity subclass) that the two aren't guaranteed to be interchangeable.
		Context ctx = getActivityDelegate().getContext();
		boolean copied;
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null) return;
			cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostic_log), dump));
			copied = true;
		} catch (Exception err) {
			// A payload this large is exactly what can trip TransactionTooLargeException crossing
			// the Binder call into the system clipboard service.
			Log.e(err, "Failed to copy the diagnostic log");
			copied = false;
		}
		// Deliberately a separate try/catch from the one above: if this dialog call is itself what's
		// failing (e.g. a WindowManager.BadTokenException because the host is already finishing by
		// the time this runs), reusing the same catch block would repeat the exact same failing call
		// as its own error path, crashing instead of just silently failing to show the confirmation.
		try {
			if (copied) UiUtils.showInfo(ctx, R.string.diagnostic_log_copied);
			else UiUtils.showAlert(ctx, R.string.diagnostic_log_copy_failed);
		} catch (Exception err) {
			Log.e(err, "Failed to show the copy result dialog");
		}
	}

	private void share() {
		String dump = DiagnosticLog.dump();
		if (dump.isEmpty()) return;
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
			UiUtils.showAlert(getActivityDelegate().getContext(),
					String.valueOf(err.getLocalizedMessage()));
		}
	}
}
