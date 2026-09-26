package me.aap.fermata.ui.view;

import static me.aap.utils.ui.fragment.ViewFragmentMediator.attachMediator;

import android.content.Context;
import android.util.AttributeSet;

import me.aap.fermata.ui.fragment.QuaternaryFabMediator;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;

/**
 * A fourth, optional, user-configurable FAB. See {@link SecondaryFloatingButton} for why it
 * always attaches one static mediator instead of participating in the primary FAB's
 * per-fragment mediator switching.
 */
public class QuaternaryFloatingButton extends FloatingButton {

	public QuaternaryFloatingButton(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected boolean setMediator(ActivityFragment f) {
		FloatingButton fb = this;
		return attachMediator(fb, f, () -> QuaternaryFabMediator.instance, this::getMediator,
				this::setMediator);
	}
}
