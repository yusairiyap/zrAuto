package me.aap.fermata.ui.view;

import static me.aap.utils.ui.fragment.ViewFragmentMediator.attachMediator;

import android.content.Context;
import android.util.AttributeSet;

import me.aap.fermata.ui.fragment.SenaryFabMediator;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;

/**
 * The sixth, optional, user-configurable FAB. See {@link SecondaryFloatingButton} for why it always
 * attaches one static mediator.
 */
public class SenaryFloatingButton extends FloatingButton {

	public SenaryFloatingButton(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected boolean setMediator(ActivityFragment f) {
		FloatingButton fb = this;
		return attachMediator(fb, f, () -> SenaryFabMediator.instance, this::getMediator,
				this::setMediator);
	}
}
