package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The frame holding YouTube's fullscreen picture (child 0 of YoutubeVideoView; the fullscreen view goes in it). A plain
 * FrameLayout now: drawing the page a second time for the glass (recording this frame and blurring it, through a lens per
 * surface or one shared layer) flickered, crashed the app natively, or showed nothing, depending on the attempt. The glass
 * over fullscreen YouTube blurs a copied picture instead (VideoBackdrop, FrameBackdrop), and nothing draws the page twice.
 */
public class GlassFrame extends FrameLayout {

	public GlassFrame(@NonNull Context ctx) {
		this(ctx, null);
	}

	public GlassFrame(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
	}
}
