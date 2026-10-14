package me.aap.fermata.ui.fragment;

import me.aap.fermata.R;

/** Settings &gt; Lyrics: the look of the lyrics on the Music tab and in their card, with a live preview. */
public class LyricsStyleFragment extends TextStyleFragment {
	@Override
	public int getFragmentId() {
		return R.id.lyrics_fragment;
	}

	@Override
	protected boolean forCaptions() {
		return false;
	}
}
