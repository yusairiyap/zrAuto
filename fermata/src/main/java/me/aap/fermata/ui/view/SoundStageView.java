package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.stage.SoundStage;
import me.aap.fermata.media.engine.stage.StageParams;

/**
 * The sound stage card: stereo width, differential surround and the 3D speaker pad, bound to the
 * app-wide {@link SoundStage} settings. Used by both effects screens, so one place changes what the
 * native player and the YouTube page play. Dragging the pad switches the 3D speaker on.
 */
public class SoundStageView extends LinearLayout implements SoundStage.Listener {
	private final SoundStage stage = SoundStage.get();
	private SwitchCompat widthSwitch;
	private SwitchCompat diffSwitch;
	private SwitchCompat posSwitch;
	private StagePadView pad;
	private EffectsUi.Row width;
	private EffectsUi.Row diffStrength;
	private EffectsUi.Row diffDelay;
	private EffectsUi.Row spread;
	private EffectsUi.Row orbit;
	private boolean refreshing;

	public SoundStageView(Context context) {
		this(context, null);
	}

	public SoundStageView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setOrientation(VERTICAL);
		setBackgroundResource(R.drawable.data_usage_card_bg);
		int inset = pd(18);
		setPadding(inset, inset, inset, inset);

		Context palette = EffectsUi.palette(context);
		LayoutInflater inflater = LayoutInflater.from(palette);
		inflater.inflate(R.layout.sound_stage, this, true);

		widthSwitch = findViewById(R.id.stage_width_switch);
		diffSwitch = findViewById(R.id.stage_diff_switch);
		posSwitch = findViewById(R.id.stage_pos_switch);
		this.pad = findViewById(R.id.stage_pad);

		LinearLayout widthRows = findViewById(R.id.stage_width_rows);
		width = EffectsUi.addRow(inflater, widthRows, R.string.stage_width, StageParams.WIDTH_MAX, 10,
				v -> v + "%", v -> stage.update(p -> p.withWidth(true, v)));

		LinearLayout diffRows = findViewById(R.id.stage_diff_rows);
		diffStrength = EffectsUi.addRow(inflater, diffRows, R.string.stage_diff_strength, 100, 5,
				v -> v + "%", v -> stage.update(p -> p.withDiff(true, v, p.diffDelayMs)));
		diffDelay = EffectsUi.addRow(inflater, diffRows, R.string.stage_diff_delay,
				StageParams.DIFF_DELAY_MAX - StageParams.DIFF_DELAY_MIN, 1,
				v -> getContext().getString(R.string.stage_ms, v + StageParams.DIFF_DELAY_MIN),
				v -> stage.update(p -> p.withDiff(true, p.diffStrength, v + StageParams.DIFF_DELAY_MIN)));

		addChips(findViewById(R.id.stage_pos_chips), inflater);
		LinearLayout posRows = findViewById(R.id.stage_pos_rows);
		spread = EffectsUi.addRow(inflater, posRows, R.string.stage_spread, StageParams.SPREAD_MAX, 5,
				v -> getContext().getString(R.string.stage_degrees, v),
				v -> stage.update(p -> p.withPosition(true, p.posX, p.posY, v, p.orbit)));
		orbit = EffectsUi.addRow(inflater, posRows, R.string.stage_orbit, StageParams.ORBIT_MAX, 5,
				this::formatOrbit,
				v -> stage.update(p -> p.withPosition(true, p.posX, p.posY, p.spread, v)));

		widthSwitch.setOnCheckedChangeListener((b, on) -> {
			if (!refreshing) stage.update(p -> p.withWidth(on, p.width));
		});
		diffSwitch.setOnCheckedChangeListener((b, on) -> {
			if (!refreshing) stage.update(p -> p.withDiff(on, p.diffStrength, p.diffDelayMs));
		});
		posSwitch.setOnCheckedChangeListener((b, on) -> {
			if (!refreshing) stage.update(p -> p.withPosition(on, p.posX, p.posY, p.spread, p.orbit));
		});
		this.pad.setListener((x, y, finished) ->
				stage.update(p -> p.withPosition(true, x, y, p.spread, p.orbit)));

		refresh(stage.getParams());
	}

	private String formatOrbit(int tenths) {
		if (tenths == 0) return getContext().getString(R.string.stage_orbit_off);
		return getContext().getString(R.string.stage_rpm,
				String.format(Locale.ROOT, "%.1f", tenths / 10f));
	}

	private void addChips(LinearLayout parent, LayoutInflater inflater) {
		addChip(parent, R.string.stage_pos_front, 0, 100);
		addChip(parent, R.string.stage_pos_left, -100, 0);
		addChip(parent, R.string.stage_pos_right, 100, 0);
		addChip(parent, R.string.stage_pos_back, 0, -100);
	}

	private void addChip(LinearLayout parent, int label, int x, int y) {
		TextView chip = new TextView(EffectsUi.palette(getContext()), null, 0, R.style.MusicChip);
		chip.setText(label);
		chip.setGravity(Gravity.CENTER);
		chip.setPadding(pd(14), 0, pd(14), 0);
		chip.setOnClickListener(v -> stage.update(p -> p.withPosition(true, x, y, p.spread, p.orbit)));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, pd(40));
		lp.setMarginStart(pd(4));
		lp.setMarginEnd(pd(4));
		parent.addView(chip, lp);
	}

	private int pd(float dp) {
		return Math.round(dp * getResources().getDisplayMetrics().density);
	}

	private void refresh(StageParams p) {
		refreshing = true;
		try {
			widthSwitch.setChecked(p.widthOn);
			width.set(p.width);
			diffSwitch.setChecked(p.diffOn);
			diffStrength.set(p.diffStrength);
			diffDelay.set(p.diffDelayMs - StageParams.DIFF_DELAY_MIN);
			posSwitch.setChecked(p.posOn);
			pad.setPosition(p.posX, p.posY);
			pad.setSpread(p.spread);
			pad.setOrbit(p.orbit);
			pad.setActive(p.posOn);
			spread.set(p.spread);
			orbit.set(p.orbit);
		} finally {
			refreshing = false;
		}
	}

	@Override
	public void onSoundStageChanged(StageParams params) {
		post(() -> refresh(params));
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		stage.addListener(this);
		refresh(stage.getParams());
	}

	@Override
	protected void onDetachedFromWindow() {
		stage.removeListener(this);
		super.onDetachedFromWindow();
	}
}
