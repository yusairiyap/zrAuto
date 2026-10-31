package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import me.aap.fermata.R;

/**
 * A length in minutes as [ − ] 30 min [ + ], moved in steps of five minutes up to eight hours. 0 is no limit. The Sleep tab and
 * the Ambience tab both use it, so the same length is shown and changed the same way.
 */
public final class LengthStepper extends LinearLayout {
	public static final int STEP_MIN = 5;
	public static final int MAX_MIN = 480;
	private final IntSupplier get;
	private final IntConsumer set;
	private final TextView value;

	/**
	 * @param textColor the colour of the length in the middle
	 * @param get       the length now, in minutes
	 * @param set       stores a new length, in minutes
	 */
	public LengthStepper(Context ctx, int textColor, IntSupplier get, IntConsumer set) {
		super(ctx);
		this.get = get;
		this.set = set;
		setOrientation(HORIZONTAL);
		setGravity(Gravity.CENTER_VERTICAL);

		TextView minus = ChipFlowLayout.chip(ctx, 0, "−", false, () -> step(-1));
		minus.setGravity(Gravity.CENTER);
		minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		minus.setContentDescription(ctx.getString(R.string.sleep_length_less));

		value = new TextView(ctx);
		value.setTextColor(textColor);
		value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
		value.setTypeface(Typeface.DEFAULT_BOLD);
		value.setGravity(Gravity.CENTER);

		TextView plus = ChipFlowLayout.chip(ctx, 0, "+", false, () -> step(1));
		plus.setGravity(Gravity.CENTER);
		plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		plus.setContentDescription(ctx.getString(R.string.sleep_length_more));

		addView(minus, new LayoutParams(px(64), px(44)));
		addView(value, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
		addView(plus, new LayoutParams(px(64), px(44)));
		refresh();
	}

	/** Shows the length as it is stored now (a sound may have changed it from elsewhere). */
	public void refresh() {
		int cur = get.getAsInt();
		value.setText((cur == 0) ? getContext().getString(R.string.sleep_no_limit)
				: getContext().getString(R.string.sleep_minutes_short, cur));
	}

	/** One step down or up: to the next multiple of five, so a 20 min length goes to 15 and 25. */
	private void step(int dir) {
		int cur = get.getAsInt();
		int next = (dir < 0) ? ((cur % STEP_MIN == 0) ? cur - STEP_MIN : cur - (cur % STEP_MIN))
				: cur + STEP_MIN - (cur % STEP_MIN);
		next = Math.max(0, Math.min(MAX_MIN, next));
		set.accept(next);
		refresh();
	}

	private int px(float dp) {
		return Math.round(dp * getResources().getDisplayMetrics().density);
	}
}
