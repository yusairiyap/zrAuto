package me.aap.fermata.ui.view;

import static me.aap.utils.ui.fragment.ViewFragmentMediator.attachMediator;

import android.content.Context;
import android.util.AttributeSet;

import me.aap.fermata.ui.fragment.NonaryFabMediator;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;

/**
 * The ninth, optional, user-configurable FAB (added from Settings). See {@link SecondaryFloatingButton}
 * for why it always attaches one static mediator.
 */
public class NonaryFloatingButton extends FloatingButton {

	public NonaryFloatingButton(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected boolean setMediator(ActivityFragment f) {
		FloatingButton fb = this;
		return attachMediator(fb, f, () -> NonaryFabMediator.instance, this::getMediator,
				this::setMediator);
	}
}
