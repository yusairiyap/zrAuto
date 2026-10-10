package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * @author Andrey Pavlenko
 */
public class ControlPanelSeekView extends AppCompatSeekBar {

	public ControlPanelSeekView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public ControlPanelSeekView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	public void setEnabled(boolean enabled) {
		if (isEnabled() == enabled) return;
		super.setEnabled(enabled);
		// Something seekable or not picks a different arrangement: see ControlPanelView#applyLayout.
		if (getParent() instanceof ControlPanelView p) p.applyLayout();
	}
}
