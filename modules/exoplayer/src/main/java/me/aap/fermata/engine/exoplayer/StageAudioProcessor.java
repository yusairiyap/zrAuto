package me.aap.fermata.engine.exoplayer;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import me.aap.fermata.media.engine.stage.SoundStage;
import me.aap.fermata.media.engine.stage.StageDsp;
import me.aap.fermata.media.engine.stage.StageParams;

/**
 * Runs the sound stage ({@link StageDsp}: stereo width, differential surround, 3D position) on
 * ExoPlayer's audio, in the player's own audio pipeline, so it needs no root and no system effect.
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
	private StageDsp dsp;
	private float[] samples = new float[0];

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
		dsp = new StageDsp(inputAudioFormat.sampleRate);
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
		StageDsp dsp = this.dsp;

		if ((dsp == null) || !p.isActive()) {
			scratch.put(input);
		} else {
			ByteOrder order = input.order();
			input.order(ByteOrder.nativeOrder());
			int frames = size / 4;
			if (samples.length < frames * 2) samples = new float[frames * 2];

			for (int i = 0, n = frames * 2; i < n; i++) {
				samples[i] = input.getShort() / 32768f;
			}

			dsp.process(samples, 0, samples, 0, frames, p);

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
