package me.aap.fermata.engine.exoplayer;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import me.aap.fermata.media.engine.stage.FxDsp;
import me.aap.fermata.media.engine.stage.FxParams;
import me.aap.fermata.media.engine.stage.FxSettings;
import me.aap.fermata.media.engine.stage.SoundStage;
import me.aap.fermata.media.engine.stage.StageParams;

/**
 * Runs the sound stage ({@link me.aap.fermata.media.engine.stage.StageDsp}: stereo width,
 * differential surround, 3D position) -- and, for a downloaded YouTube video, the YouTube
 * equalizer's effects ({@link FxDsp}: equalizer, bass, virtualizer, Live Hall) -- on ExoPlayer's
 * audio, in the player's own audio pipeline, so it needs no root and no system effect.
 * Takes 16-bit stereo, which is what the decoders produce here, and lets anything else through
 * untouched. Settings are read from {@link SoundStage} for every block, so changes apply at once;
 * while nothing is switched on the audio is just copied through.
 */
@UnstableApi
final class StageAudioProcessor implements AudioProcessor {
	private AudioFormat format = AudioFormat.NOT_SET;
	private ByteBuffer scratch = EMPTY_BUFFER;
	private ByteBuffer output = EMPTY_BUFFER;
	private boolean inputEnded;
	private FxDsp dsp;
	// Whether the last block went through the effects.
	private boolean processing;
	/** Whether the YouTube equalizer's effects apply to what is playing: only for a downloaded video. */
	private volatile boolean fx;
	private float[] samples = new float[0];

	/** The YouTube equalizer's effects on or off for what plays from now on. */
	void setFx(boolean on) {
		fx = on;
	}

	@Override
	public long getDurationAfterProcessorApplied(long durationUs) {
		return durationUs;
	}

	@NonNull
	@Override
	public AudioFormat configure(@NonNull AudioFormat inputAudioFormat) {
		if ((inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) || (inputAudioFormat.channelCount != 2)) {
			format = AudioFormat.NOT_SET;
			dsp = null;
			return AudioFormat.NOT_SET;
		}

		format = inputAudioFormat;
		dsp = new FxDsp(inputAudioFormat.sampleRate);
		return inputAudioFormat;
	}

	@Override
	public boolean isActive() {
		return format != AudioFormat.NOT_SET;
	}

	@Override
	public void queueInput(@NonNull ByteBuffer input) {
		int size = input.remaining();
		if (size == 0) return;

		if (scratch.capacity() < size) {
			scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
		} else {
			scratch.clear();
		}

		StageParams p = SoundStage.get().getParams();
		FxParams f = fx ? FxSettings.get().getParams() : FxParams.OFF;
		FxDsp dsp = this.dsp;

		if ((dsp == null) || (!p.isActive() && !f.isActive())) {
			// Effects just switched off: what the filters and the hall still hold must not come back as
			// a burst when they are switched on again.
			if (processing && (dsp != null)) dsp.reset();
			processing = false;
			scratch.put(input);
		} else {
			processing = true;
			ByteOrder order = input.order();
			input.order(ByteOrder.nativeOrder());
			int frames = size / 4;
			if (samples.length < frames * 2) samples = new float[frames * 2];

			for (int i = 0, n = frames * 2; i < n; i++) {
				samples[i] = input.getShort() / 32768f;
			}

			dsp.process(samples, frames, f, p);

			for (int i = 0, n = frames * 2; i < n; i++) {
				int v = Math.round(samples[i] * 32767f);
				scratch.putShort((short) Math.max(-32768, Math.min(32767, v)));
			}

			input.order(order);
			// A trailing partial frame (never expected) is passed on as it is.
			if (input.hasRemaining()) scratch.put(input);
		}

		scratch.flip();
		output = scratch;
	}

	@Override
	public void queueEndOfStream() {
		inputEnded = true;
	}

	@NonNull
	@Override
	public ByteBuffer getOutput() {
		ByteBuffer o = output;
		output = EMPTY_BUFFER;
		return o;
	}

	@Override
	public boolean isEnded() {
		return inputEnded && (output == EMPTY_BUFFER);
	}

	@Override
	public void flush() {
		output = EMPTY_BUFFER;
		inputEnded = false;
		if (dsp != null) dsp.reset();
	}

	@Override
	public void reset() {
		flush();
		scratch = EMPTY_BUFFER;
		samples = new float[0];
		dsp = null;
		format = AudioFormat.NOT_SET;
	}
}
