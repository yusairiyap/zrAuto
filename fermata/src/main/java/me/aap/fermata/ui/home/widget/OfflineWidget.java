package me.aap.fermata.ui.home.widget;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.util.NetworkState;

/**
 * Offline mode as one big tap (the OFFLINE_TOGGLE action): the picture is the state, the line under it says what it means.
 * Follows the state while on screen, whatever changed it (a key, a floating button, a stall).
 */
final class OfflineWidget extends HomeWidgetView {
	private final NetworkState.Listener listener = online -> refresh();
	private LinearLayout column;
	private ImageView picture;
	private TextView title;
	private TextView sub;

	OfflineWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.setGravity(Gravity.CENTER);
		picture = icon(R.drawable.wifi, 40, ROLE_ICON);
		column.addView(picture, new LinearLayout.LayoutParams(px(44), px(44)));
		title = text(ROLE_PRIMARY, 15, true);
		title.setText(R.string.action_offline_toggle);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tlp.topMargin = px(6);
		column.addView(title, tlp);
		sub = text(ROLE_SECONDARY, 12, false);
		sub.setGravity(Gravity.CENTER);
		column.addView(sub);
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
		// The whole card is the switch.
		setClickable(true);
		setOnClickListener(v -> host.run(Action.OFFLINE_TOGGLE));
	}

	@Override
	protected void onBind() {
		refresh();
	}

	@Override
	protected void onActive() {
		NetworkState.addListener(listener);
		refresh();
	}

	@Override
	protected void onInactive() {
		NetworkState.removeListener(listener);
	}

	private void refresh() {
		if (column == null) return;
		boolean forced = NetworkState.isForcedOffline();
		boolean down = !NetworkState.isOnline();
		picture.setImageResource(down ? R.drawable.network_off : R.drawable.wifi);
		sub.setText(forced ? R.string.offline_widget_on : down ? R.string.no_network_title : R.string.offline_widget_off);
		// A short card has room for the picture and one line only.
		sub.setVisibility((heightPx() > px(100)) ? VISIBLE : GONE);
	}

	@Override
	protected void onResized(int w, int h) {
		refresh();
	}
}
