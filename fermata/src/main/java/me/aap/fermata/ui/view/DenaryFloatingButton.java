package me.aap.fermata.ui.view;

import static me.aap.utils.ui.fragment.ViewFragmentMediator.attachMediator;

import android.content.Context;
import android.util.AttributeSet;

import me.aap.fermata.ui.fragment.DenaryFabMediator;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;

/**
 * The tenth, optional, user-configurable FAB (added from Settings). See {@link SecondaryFloatingButton}
 * for why it always attaches one static mediator.
 */
public class DenaryFloatingButton extends FloatingButton {

	public DenaryFloatingButton(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected boolean setMediator(ActivityFragment f) {
		FloatingButton fb = this;
		return attachMediator(fb, f, () -> DenaryFabMediator.instance, this::getMediator,
				this::setMediator);
	}
}
