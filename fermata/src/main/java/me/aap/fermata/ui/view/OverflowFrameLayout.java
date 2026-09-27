package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A FrameLayout whose children may draw past its own edges (with clipChildren off) even while
 * it's being faded: a view reporting overlapping rendering is drawn through an offscreen buffer
 * the size of its own bounds whenever its alpha is below 1, which would cut such children off for
 * the whole fade (e.g. the Music tab's background reaching under a side nav pill, during the
 * tab-switch crossfade) and pop them back in at its end. Here the alpha is applied to each child
 * directly instead.
 */
public class OverflowFrameLayout extends FrameLayout {

	public OverflowFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	public boolean hasOverlappingRendering() {
		return false;
	}
}
