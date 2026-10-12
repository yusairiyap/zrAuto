package me.aap.fermata.ui.view;

import static me.aap.utils.ui.fragment.ViewFragmentMediator.attachMediator;

import android.content.Context;
import android.util.AttributeSet;

import me.aap.fermata.ui.fragment.OctonaryFabMediator;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;

/**
 * The eighth, optional, user-configurable FAB (added from Settings). See {@link SecondaryFloatingButton}
 * for why it always attaches one static mediator.
 */
public class OctonaryFloatingButton extends FloatingButton {

	public OctonaryFloatingButton(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected boolean setMediator(ActivityFragment f) {
		FloatingButton fb = this;
		return attachMediator(fb, f, () -> OctonaryFabMediator.instance, this::getMediator,
				this::setMediator);
	}
}
