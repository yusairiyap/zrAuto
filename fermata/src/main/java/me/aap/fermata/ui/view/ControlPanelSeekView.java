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

	private android.content.res.ColorStateList artProgress;
	private android.content.res.ColorStateList artThumb;
	private final Runnable artStep = this::applyArt;

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (artProgress == null) artProgress = getProgressTintList();
		if (artThumb == null) artThumb = getThumbTintList();
		ArtAccent.addListener(artStep);
		applyArt();
	}

	@Override
	protected void onDetachedFromWindow() {
		ArtAccent.removeListener(artStep);
		super.onDetachedFromWindow();
	}

	/** The colours the theme gave, with its blue replaced by the cover's while "Follow the album art" is on. */
	private void applyArt() {
		if (artProgress != null) setProgressTintList(ArtAccent.mapList(artProgress));
		if (artThumb != null) setThumbTintList(ArtAccent.mapList(artThumb));
	}
}
