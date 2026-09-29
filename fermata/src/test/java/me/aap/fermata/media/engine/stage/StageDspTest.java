package me.aap.fermata.media.engine.stage;

import org.junit.Assert;
import org.junit.Test;

public class StageDspTest {
	private static final int RATE = 48000;

	/** Runs a two-tone stereo signal through the DSP in blocks; returns {left rms, right rms}. */
	private static double[] run(StageParams p, double leftHz, double rightHz) {
		int n = RATE;
		float[] buf = new float[2 * n];

		for (int i = 0; i < n; i++) {
			buf[2 * i] = (float) (0.5 * Math.sin(2 * Math.PI * leftHz * i / RATE));
			buf[2 * i + 1] = (float) (0.5 * Math.sin(2 * Math.PI * rightHz * i / RATE));
		}

		StageDsp dsp = new StageDsp(RATE);
		for (int off = 0; off < n; off += 1024) {
			dsp.process(buf, 2 * off, buf, 2 * off, Math.min(1024, n - off), p);
		}

		double l = 0;
		double r = 0;
		for (int i = 0; i < n; i++) {
			Assert.assertFalse(Float.isNaN(buf[2 * i]) || Float.isNaN(buf[2 * i + 1]));
			l += buf[2 * i] * buf[2 * i];
			r += buf[2 * i + 1] * buf[2 * i + 1];
		}
		return new double[]{Math.sqrt(l / n), Math.sqrt(r / n)};
	}

	@Test
	public void offLeavesTheSignalAlone() {
		double[] dry = run(StageParams.OFF, 1000, 1000);
		Assert.assertEquals(0.3535, dry[0], 0.01);
		Assert.assertEquals(dry[0], dry[1], 0.001);
	}

	@Test
	public void zeroWidthMakesMono() {
		StageParams p = StageParams.OFF.withWidth(true, 0);
		double[] out = run(p, 1000, 1300);
		Assert.assertEquals(out[0], out[1], 0.001);
	}

	@Test
	public void soundFromTheRightIsLouderInTheRightEar() {
		StageParams p = StageParams.OFF.withPosition(true, 100, 0, 30, 0);
		double[] out = run(p, 1000, 1000);
		Assert.assertTrue(out[1] > out[0] * 1.5);
	}

	@Test
	public void soundFromTheLeftIsLouderInTheLeftEar() {
		StageParams p = StageParams.OFF.withPosition(true, -100, 0, 30, 0);
		double[] out = run(p, 1000, 1000);
		Assert.assertTrue(out[0] > out[1] * 1.5);
	}

	@Test
	public void positionInTheMiddleIsPlainStereo() {
		double[] dry = run(StageParams.OFF, 1000, 1300);
		double[] out = run(StageParams.OFF.withPosition(true, 0, 0, 30, 0), 1000, 1300);
		Assert.assertEquals(dry[0], out[0], 0.001);
		Assert.assertEquals(dry[1], out[1], 0.001);
	}

	@Test
	public void differentialSurroundStaysWithinRange() {
		double[] out = run(StageParams.OFF.withDiff(true, 100, 20), 1000, 1300);
		Assert.assertTrue(out[0] < 1 && out[1] < 1);
	}

	@Test
	public void paramsAreClamped() {
		StageParams p = StageParams.OFF.withPosition(true, 500, -500, 500, 5000);
		Assert.assertEquals(100, p.posX);
		Assert.assertEquals(-100, p.posY);
		Assert.assertEquals(StageParams.SPREAD_MAX, p.spread);
		Assert.assertEquals(StageParams.ORBIT_MAX, p.orbit);
		Assert.assertEquals(0, StageParams.OFF.withWidth(true, -5).width);
	}
}
