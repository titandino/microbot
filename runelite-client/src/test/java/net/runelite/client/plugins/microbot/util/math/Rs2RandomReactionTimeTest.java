package net.runelite.client.plugins.microbot.util.math;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class Rs2RandomReactionTimeTest {

	private static final int SAMPLE_SIZE = 20_000;
	private static final int MIN_MS = 120;
	private static final int MAX_MS = 2_200;

	@Test
	public void reactionTimeStaysWithinHumanBounds() {
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			int v = Rs2Random.reactionTime();
			assertTrue("reaction time " + v + " below floor " + MIN_MS, v >= MIN_MS);
			assertTrue("reaction time " + v + " above ceiling " + MAX_MS, v <= MAX_MS);
		}
	}

	@Test
	public void reactionTimeMedianMatchesHumanAverage() {
		int[] samples = new int[SAMPLE_SIZE];
		for (int i = 0; i < SAMPLE_SIZE; i++) samples[i] = Rs2Random.reactionTime();
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		assertTrue("median " + median + " should sit in the ~260ms reaction-time regime [180,360]",
				median >= 180 && median <= 360);
	}

	@Test
	public void reactionTimeHasRightSkew() {
		int[] samples = new int[SAMPLE_SIZE];
		long sum = 0;
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			samples[i] = Rs2Random.reactionTime();
			sum += samples[i];
		}
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		double mean = sum / (double) SAMPLE_SIZE;
		assertTrue("log-normal mean (" + mean + ") must exceed the median (" + median + ") by a healthy margin",
				mean - median >= 10);
	}

	@Test
	public void reactionTimeWithExplicitMedianTracksInput() {
		int target = 800;
		int[] samples = new int[SAMPLE_SIZE];
		for (int i = 0; i < SAMPLE_SIZE; i++) samples[i] = Rs2Random.reactionTime(target);
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		assertTrue("sampled median " + median + " should track requested " + target + "ms within 10%",
				Math.abs(median - target) <= target * 0.10);
	}
}
