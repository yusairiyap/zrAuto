package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * A slider with a minus and a plus button beside it: tapping a button moves the value one step,
 * holding it keeps moving (faster after a moment). Made for touch screens in a car, where dragging a
 * thumb precisely is the hard part. The steps reach the slider's listener as if the thumb was
 * dragged ({@code fromUser} true), so whatever the listener does for a drag it does for a step.
 */
public class StepSeekBar extends AppCompatSeekBar {
	private static final long REPEAT_DELAY = 380;
	private static final long REPEAT_INTERVAL = 70;

	private OnSeekBarChangeListener listener;
	private int step;

	public StepSeekBar(Context context) {
		super(context);
	}

	public StepSeekBar(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public StepSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {
		listener = l;
		super.setOnSeekBarChangeListener(l);
	}

	/** How far one button press moves the value; 0 = a twentieth of the range. */
	public void setStep(int step) {
		this.step = step;
	}

	/** Moves the value one step up ({@code direction} > 0) or down, as if the thumb was dragged. */
	public void stepBy(int direction) {
		int s = (step > 0) ? step : Math.max(1, getMax() / 20);
		int p = Math.max(0, Math.min(getMax(), getProgress() + ((direction > 0) ? s : -s)));
		if (p == getProgress()) return;

		OnSeekBarChangeListener l = listener;
		// Set the value without the listener seeing a programmatic change: it only cares about drags.
		super.setOnSeekBarChangeListener(null);
		setProgress(p);
		super.setOnSeekBarChangeListener(l);

		if (l != null) {
			l.onStartTrackingTouch(this);
			l.onProgressChanged(this, p, true);
			l.onStopTrackingTouch(this);
		}
	}

	/** Makes {@code minus} and {@code plus} step this slider; either may be null. */
	public void bindButtons(View minus, View plus) {
		bind(minus, -1);
		bind(plus, 1);
	}

	private void bind(View b, int direction) {
		if (b == null) return;

		Runnable repeat = new Runnable() {
			@Override
			public void run() {
				stepBy(direction);
				b.postDelayed(this, REPEAT_INTERVAL);
			}
		};

		b.setOnTouchListener((v, e) -> {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					v.setPressed(true);
					stepBy(direction);
					v.postDelayed(repeat, REPEAT_DELAY);
					return true;
				case MotionEvent.ACTION_UP:
					v.performClick();
					// fall through
				case MotionEvent.ACTION_CANCEL:
					v.setPressed(false);
					v.removeCallbacks(repeat);
					return true;
				default:
					return true;
			}
		});
		// Only reached from the keyboard / D-pad (a touch is consumed above, and performClick() from
		// it lands here too, hence the nothing-to-do check): one step per press.
		b.setOnClickListener(v -> {
		});
		b.setOnKeyListener((v, keyCode, e) -> {
			if ((e.getAction() == android.view.KeyEvent.ACTION_DOWN) &&
					((keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER) ||
							(keyCode == android.view.KeyEvent.KEYCODE_ENTER))) {
				stepBy(direction);
				return true;
			}
			return false;
		});
	}
}
