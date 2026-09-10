package me.aap.fermata.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.AudioEffects;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.view.AudioEffectsView;
import me.aap.fermata.ui.view.ControlPanelView;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.ui.view.NavBarView;

/**
 * @author Andrey Pavlenko
 */
public class AudioEffectsFragment extends MainActivityFragment implements
		MediaSessionCallback.Listener, MainActivityListener {

	@Override
	public int getFragmentId() {
		return R.id.audio_effects_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getResources().getString(R.string.audio_effects);
	}

	@SuppressLint("RestrictedApi")
	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getMainActivity().onSuccess(a -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			a.addBroadcastListener(this, ACTIVITY_FINISH | ACTIVITY_DESTROY | BARS_HIDDEN_CHANGED);
			b.getMediaSessionCallback().addBroadcastListener(this);
		});
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		getMainActivity().onSuccess(this::removeListeners);
	}

	private void removeListeners(MainActivityDelegate a) {
		a.removeBroadcastListener(this);
		a.getMediaSessionCallback().removeBroadcastListener(this);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return new AudioEffectsView(getContext());
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		onHiddenChanged(isHidden());

		getMainActivity().onSuccess(a -> {
			ControlPanelView cp = a.getControlPanel();
			NavBarView nb = a.getNavBar();
			if (cp != null) cp.addOnLayoutChangeListener(panelSync);
			if (nb != null) nb.addOnLayoutChangeListener(panelSync);
		});
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		getMainActivity().onSuccess(a -> {
			ControlPanelView cp = a.getControlPanel();
			NavBarView nb = a.getNavBar();
			if (cp != null) cp.removeOnLayoutChangeListener(panelSync);
			if (nb != null) nb.removeOnLayoutChangeListener(panelSync);

			FermataServiceUiBinder b = a.getMediaServiceBinder();
			AudioEffectsView view = getView();
			if (view == null) return;
			view.apply(b.getMediaSessionCallback());
		});
	}

	/**
	 * control_panel's/nav_bar's own height is only known-good the moment
	 * {@link #applyBarsHiddenMargins} last ran -- e.g. the seek bar (and with it, the panel's
	 * height) can enable/disable well after this screen was first shown, as playback starts,
	 * stops, or the source changes, which none of onHiddenChanged/BARS_HIDDEN_CHANGED alone would
	 * ever catch. Re-syncing off their own layout changes closes that gap instead of leaving the
	 * bottom clearance stuck at whatever it happened to be when the screen was opened.
	 */
	private final View.OnLayoutChangeListener panelSync = (v, left, top, right, bottom, oldLeft,
																													oldTop, oldRight, oldBottom) ->
			getMainActivity().onSuccess(this::applyBarsHiddenMargins);

	@Nullable
	@Override
	public AudioEffectsView getView() {
		return (AudioEffectsView) super.getView();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);

		getMainActivity().onSuccess(a -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			AudioEffectsView view = getView();
			if (view == null) return;
			MediaSessionCallback cb = b.getMediaSessionCallback();

			if (hidden) {
				view.apply(cb);
				view.cleanup();
				return;
			}

			MediaEngine eng = cb.getEngine();

			if (eng != null) {
				PlayableItem pi = eng.getSource();

				if (pi != null) {
					AudioEffects effects = eng.ensureAudioEffects();

					if (effects != null) {
						view.init(cb, effects, pi);
						// Bars may already be hidden from before this screen was ever opened (that state
						// is app-wide and outlives this fragment being shown/hidden) -- sync once here
						// rather than only reacting to BARS_HIDDEN_CHANGED, which only fires on a change.
						applyBarsHiddenMargins(a);
						return;
					}
				}
			}

			close(a);
		});
	}

	@Override
	public boolean onBackPressed() {
		getMainActivity().onSuccess(a -> {
			AudioEffectsView view = getView();
			if (view != null) view.apply(a.getMediaServiceBinder().getMediaSessionCallback());
			close(a);
		});
		return true;
	}

	private void applyAndCleanup(MainActivityDelegate a) {
		AudioEffectsView view = getView();

		if (view != null) {
			view.apply(a.getMediaServiceBinder().getMediaSessionCallback());
			view.cleanup();
		}
	}

	private void close(MainActivityDelegate a) {
		AudioEffectsView view = getView();

		if (view != null) {
			view.apply(a.getMediaServiceBinder().getMediaSessionCallback());
			view.cleanup();
		}

		a.backToNavFragment();
	}

	@NonNull
	private FutureSupplier<MainActivityDelegate> getMainActivity() {
		return MainActivityDelegate.getActivityDelegate(getContext());
	}

	@SuppressLint("SwitchIntDef")
	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		if (isHidden()) return;

		AudioEffectsView view;

		switch (state.getState()) {
			case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
			case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
			case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
				view = getView();

				if (view != null) {
					view.apply(cb);
					view.cleanup();
				}

				break;
			case PlaybackStateCompat.STATE_STOPPED:
				getMainActivity().onSuccess(this::close);
				break;
			default:
				MediaEngine eng = cb.getEngine();
				PlayableItem pi;
				AudioEffects effects;

				if ((eng == null) || ((pi = eng.getSource()) == null)
						|| ((effects = eng.ensureAudioEffects()) == null) || ((view = getView()) == null)) {
					getMainActivity().onSuccess(this::close);
				} else if (view.getEffects() != effects) {
					view.cleanup();
					view.init(cb, effects, pi);
				}
		}
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (e == ACTIVITY_FINISH) {
			applyAndCleanup(a);
		} else if (e == ACTIVITY_DESTROY) {
			removeListeners(a);
		} else if (e == BARS_HIDDEN_CHANGED) {
			applyBarsHiddenMargins(a);
		}
	}

	/**
	 * effects_title's topMargin (see audio_effects.xml) clears tool_bar's title, collapsing to 0
	 * while the bars are hidden.
	 * <p>
	 * The bottom is handled by shrinking this ScrollView's own bounds with a bottom margin, rather
	 * than by reserving space inside its content. Every content-side attempt here failed
	 * identically (a child's margin, ScrollView padding, a spacer View's height, equalizer_channels'
	 * padding) because they all depend on the child measuring and scrolling to its full height, and
	 * it wasn't: equalizer_channels used to be pinned with constraintBottom_toBottomOf="parent"
	 * inside a wrap_content parent, which positions a child within the already-resolved parent
	 * height instead of growing it, so the bands overflowed a too-short container and apply_to was
	 * never even reachable by scrolling. That pin is gone now, but a margin here is the mechanism
	 * that cannot fail regardless: content simply has no bounds to be drawn in beneath the panel.
	 * The same hard-guarantee reasoning as {@link MainActivityDelegate#insetWebViewTop}.
	 * <p>
	 * Sized from {@link ControlPanelView#getPanelHeight()}/{@link NavBarView#getBarSize()}: both
	 * are deterministic, preference-scaled values known synchronously once bound, whereas the
	 * equivalent {@code getHeight()} reads on this screen have proven unreliable (likely
	 * stale/zero), and a flat dp guess silently falls short whenever the user raises the control
	 * panel size preference above its default.
	 * <p>
	 * Note the asymmetry between the two ends: {@link MainActivityDelegate#isBarsHidden()} governs
	 * the top alone, because {@link MainActivityDelegate#setBarsHidden} only hides tool_bar and
	 * nav_bar. control_panel is not one of those bars (it carries the very button that toggles
	 * them) and stays on screen either way, so the bottom follows each bar's own visibility
	 * instead. Re-applied every time rather than just once since this fragment's view is only ever
	 * shown/hidden, never recreated, so it wouldn't otherwise get re-touched.
	 */
	private void applyBarsHiddenMargins(MainActivityDelegate a) {
		AudioEffectsView view = getView();
		if (view == null) return;

		View header = view.findViewById(R.id.effects_title);
		if (header == null) return;

		// Only tool_bar is actually gone while the bars are hidden, so only the top collapses.
		int top = a.isBarsHidden() ? 0
				: getResources().getDimensionPixelSize(R.dimen.audio_effects_top_margin);
		int bottom = 0;

		// Deliberately keyed off each bar's own visibility rather than isBarsHidden():
		// setBarsHidden() only hides tool_bar and nav_bar, never control_panel, which keeps
		// overlapping this screen (it's the bar the hide-bars button itself lives on).
		ControlPanelView cp = a.getControlPanel();
		if ((cp != null) && (cp.getVisibility() == View.VISIBLE)) bottom = cp.getPanelHeight();

		// control_panel sits above nav_bar rather than the other way around when nav_bar is
		// bottom-positioned, so clearing control_panel alone isn't enough -- nav_bar needs its own
		// extra clearance stacked on top of that.
		NavBarView nb = a.getNavBar();
		if ((nb != null) && (nb.getVisibility() == View.VISIBLE)
				&& (a.getPrefs().getNavBarPosPref(a) == NavBarView.POSITION_BOTTOM)) {
			bottom += nb.getBarSize();
		}

		if (header.getLayoutParams() instanceof ViewGroup.MarginLayoutParams hlp) {
			hlp.topMargin = top;
			header.setLayoutParams(hlp);
		}
		if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams vlp) {
			if (vlp.bottomMargin != bottom) {
				vlp.bottomMargin = bottom;
				view.setLayoutParams(vlp);
			}
		}
	}
}
