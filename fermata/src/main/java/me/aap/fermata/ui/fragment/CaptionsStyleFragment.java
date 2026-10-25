package me.aap.fermata.ui.fragment;

import me.aap.fermata.R;

/** Settings &gt; Captions: the look of the captions over fullscreen video, with a live preview. */
public class CaptionsStyleFragment extends TextStyleFragment {
	@Override
	public int settingsEntryTitle() {
		return me.aap.fermata.R.string.captions;
	}

	@Override
	public int getFragmentId() {
		return R.id.captions_fragment;
	}

	@Override
	protected boolean forCaptions() {
		return true;
	}
}
