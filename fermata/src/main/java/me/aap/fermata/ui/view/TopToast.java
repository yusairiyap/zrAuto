package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.app.App;
import me.aap.utils.ui.UiUtils;

/**
 * A short message as a {@link TopPopup} card instead of an Android toast: a toast only ever shows on
 * the phone, this one shows on the car's screen too (on the car's while Android Auto runs the app,
 * see {@link MainActivityDelegate#getUiDelegate()}). Gone by itself after a few seconds; falls back
 * to a toast when there is no screen to show it on.
 */
public final class TopToast {
	private static final long SHOW_MS = 3500;
	private static final long SHOW_WITH_ACTION_MS = 6000;
	private static final int BG = 0xFF263238;
	private static final int FG = 0xFFFFFFFF;

	private TopToast() {
	}

	public static void show(@DrawableRes int icon, @NonNull CharSequence title) {
		show(icon, title, null, 0, null);
	}

	public static void show(@DrawableRes int icon, @StringRes int title, Object... args) {
		Context ctx = App.get();
		show(icon, ctx.getString(title, args));
	}

	/**
	 * @param action run when the card's button is tapped (with the card taken down), the button
	 *               labelled {@code actionText}; no button when null
	 */
	public static void show(@DrawableRes int icon, @NonNull CharSequence title,
													@Nullable CharSequence text, @StringRes int actionText,
													@Nullable Runnable action) {
		// Any thread: everything below is UI work.
		if (Looper.myLooper() != Looper.getMainLooper()) {
			new Handler(Looper.getMainLooper()).post(() -> show(icon, title, text, actionText, action));
			return;
		}

		MainActivityDelegate a = MainActivityDelegate.getUiDelegate();
		if ((a == null) || !showOn(a, icon, title, text, actionText, action)) {
			UiUtils.showToast(App.get(), (text == null) ? title.toString() : title + "\n" + text);
		}
	}

	private static boolean showOn(MainActivityDelegate a, @DrawableRes int icon, CharSequence title,
																@Nullable CharSequence text, @StringRes int actionText,
																@Nullable Runnable action) {
		Context ctx = a.getContext();
		View b;
		try {
			b = LayoutInflater.from(ctx).inflate(R.layout.data_usage_banner, null, false);
		} catch (RuntimeException err) {
			return false;
		}
		b.setElevation(UiUtils.toIntPx(ctx, 8));
		b.setBackgroundTintList(ColorStateList.valueOf(BG));
		ImageView iv = b.findViewById(R.id.data_usage_banner_icon);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(BG));
		iv.setBackgroundTintList(ColorStateList.valueOf(FG));
		TextView t = b.findViewById(R.id.data_usage_banner_title);
		t.setTextColor(FG);
		t.setText(title);
		TextView tx = b.findViewById(R.id.data_usage_banner_text);
		if (text == null) {
			tx.setVisibility(View.GONE);
		} else {
			tx.setTextColor(FG);
			tx.setAlpha(0.85f);
			tx.setText(text);
		}
		TextView act = b.findViewById(R.id.data_usage_banner_action);
		if ((action == null) || (actionText == 0)) {
			act.setVisibility(View.GONE);
		} else {
			act.setBackgroundTintList(ColorStateList.valueOf(FG));
			act.setTextColor(BG);
			act.setText(actionText);
			act.setOnClickListener(v -> {
				TopPopup.dismiss(b);
				action.run();
			});
		}
		ImageButton close = b.findViewById(R.id.data_usage_banner_close);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(FG));
		close.setOnClickListener(v -> TopPopup.dismiss(b));

		if (!TopPopup.show(a, b, null)) return false;
		b.postDelayed(() -> TopPopup.dismiss(b), (action == null) ? SHOW_MS : SHOW_WITH_ACTION_MS);
		return true;
	}
}
