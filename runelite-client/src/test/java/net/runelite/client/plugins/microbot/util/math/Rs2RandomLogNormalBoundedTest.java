package net.runelite.client.plugins.microbot.util.math;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class Rs2RandomLogNormalBoundedTest {

	private static final int SAMPLE_SIZE = 40_000;

	@Test
	public void samplesStayInsideBounds() {
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			int v = Rs2Random.logNormalBounded(20, 200);
			assertTrue("sample " + v + " below floor", v >= 20);
			assertTrue("sample " + v + " above ceiling", v <= 200);
		}
	}

	@Test
	public void medianSitsNearGeometricMean() {
		int[] samples = new int[SAMPLE_SIZE];
		for (int i = 0; i < SAMPLE_SIZE; i++) samples[i] = Rs2Random.logNormalBounded(20, 200);
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		double geometricMean = Math.sqrt(20.0 * 200.0); // ≈ 63.2
		assertTrue("median " + median + " should be within 20% of geometric mean " + geometricMean,
				Math.abs(median - geometricMean) < geometricMean * 0.20);
	}

	@Test
	public void distributionIsRightSkewed() {
		int[] samples = new int[SAMPLE_SIZE];
		long sum = 0;
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			samples[i] = Rs2Random.logNormalBounded(20, 200);
			sum += samples[i];
		}
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		double mean = sum / (double) SAMPLE_SIZE;
		assertTrue("mean " + mean + " should exceed median " + median + " (right-skewed)",
				mean > median);
	}

	@Test
	public void degenerateBoundsDoNotExplode() {
		int v = Rs2Random.logNormalBounded(50, 50);
		assertTrue(v == 50);
	}

	@Test
	public void handlesSwappedBoundsGracefully() {
		int v = Rs2Random.logNormalBounded(100, 50);
		assertTrue(v == 100);
	}
}
