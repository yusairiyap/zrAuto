package me.aap.fermata.addon.web;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.LEFT;
import static java.util.Objects.requireNonNull;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;
import static me.aap.utils.ui.UiUtils.toPx;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.constraintlayout.widget.ConstraintLayout;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public class WebToolBarMediator implements ToolBarView.Mediator {
	private static final WebToolBarMediator instance = new WebToolBarMediator();

	public static WebToolBarMediator getInstance() {
		return instance;
	}

	@Override
	public void enable(ToolBarView tb, ActivityFragment f) {
		dynCtx(f.requireContext());
		WebBrowserFragment b = (WebBrowserFragment) f;
		EditText t = createAddress(tb, b);
		String url = b.getUrl();
		if (url != null) t.setText(url);
		addView(tb, t, R.id.browser_addr, LEFT);
		addButton(tb, R.drawable.forward, v ->
				requireNonNull(b.getWebView()).goForward(), R.id.browser_forward, LEFT)
				.setToolBarPriority(2);
		// Back and clear never move into the toolbar's "more" menu (see ToolBarView#onMeasure()).
		addButton(tb, me.aap.utils.R.drawable.back, v ->
				requireNonNull(b.getWebView()).goBack(), me.aap.utils.R.id.tool_bar_back_button, LEFT)
				.setToolBarPriority(Integer.MAX_VALUE);
		addButton(tb, R.drawable.clear, v -> t.setText(""), R.id.browser_addr_clear)
				.setToolBarPriority(Integer.MAX_VALUE);
		addButton(tb, me.aap.fermata.R.drawable.bookmark_filled, v ->
				onBookmarksButtonClick(b), me.aap.fermata.R.id.bookmarks);
		ImageButton pm = addButton(tb, me.aap.fermata.R.drawable.private_mode, v ->
				onPrivateModeButtonClick((ImageButton) v), me.aap.fermata.R.id.private_mode);
		updatePrivateModeButton(pm);
		FermataWebView wv = b.getWebView();
		setButtonsVisibility(tb, (wv != null) && wv.canGoBack(), (wv != null) && wv.canGoForward());
		ToolBarView.Mediator.super.enable(tb, f);
	}

	@Override
	public void onActivityEvent(ToolBarView tb, ActivityDelegate a, long e) {
		if (e == FRAGMENT_CONTENT_CHANGED) {
			ImageButton pm = tb.findViewById(me.aap.fermata.R.id.private_mode);
			if (pm != null) updatePrivateModeButton(pm);
		}
	}

	private void onBookmarksButtonClick(WebBrowserFragment f) {
		f.getActivityDelegate().getToolBarMenu().show(f::bookmarksMenu);
	}

	private void onPrivateModeButtonClick(ImageButton b) {
		MainActivityPrefs p = MainActivityPrefs.get();
		boolean enable = !p.isPrivateModeEnabled();
		Log.i("Private Mode toolbar button tapped, enabling=" + enable);
		p.setPrivateModeEnabled(enable);
		updatePrivateModeButton(b);
	}

	private void updatePrivateModeButton(ImageButton b) {
		if (MainActivityPrefs.get().isPrivateModeEnabled()) {
			b.setColorFilter(0xFF8A5CF6);
			b.setContentDescription(b.getContext().getString(me.aap.fermata.R.string.private_mode_on));
		} else {
			b.clearColorFilter();
			b.setContentDescription(b.getContext().getString(me.aap.fermata.R.string.private_mode_off));
		}
	}

	public void setAddress(ToolBarView tb, String addr) {
		EditText et = tb.findViewById(R.id.browser_addr);
		if (et != null) et.setText(addr);
	}

	public void setButtonsVisibility(ToolBarView tb, boolean back, boolean forward) {
		tb.findViewById(me.aap.utils.R.id.tool_bar_back_button).setVisibility(back ? VISIBLE : GONE);
		tb.findViewById(R.id.browser_forward).setVisibility(forward ? VISIBLE : GONE);
	}

	private EditText createAddress(ToolBarView tb, WebBrowserFragment f) {
		EditText t = createEditText(tb);
		ConstraintLayout.LayoutParams lp = setLayoutParams(t, 0, WRAP_CONTENT);
		lp.horizontalWeight = 2;
		t.setBackgroundResource(me.aap.utils.R.color.tool_bar_edittext_bg);
		t.setOnKeyListener((v, keyCode, event) -> onKey(f, t, keyCode, event));
		t.setMaxLines(1);
		t.setSingleLine(true);
		return t;
	}

	private boolean onKey(WebBrowserFragment f, EditText text, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

		switch (keyCode) {
			case KEYCODE_DPAD_CENTER:
			case KEYCODE_ENTER:
			case KEYCODE_NUMPAD_ENTER:
				f.loadUrl(text.getText().toString());
				return true;
			default:
				return UiUtils.dpadFocusHelper(text, keyCode, event);
		}
	}
}
