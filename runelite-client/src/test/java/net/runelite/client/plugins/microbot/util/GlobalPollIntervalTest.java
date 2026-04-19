package net.runelite.client.plugins.microbot.util;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlobalPollIntervalTest {

	private static final int SAMPLE_SIZE = 20_000;
	private static final int MIN_MS = 40;
	private static final int MAX_MS = 320;
	private static final int DISTINCT_VALUES_MIN = 50;
	private static final int LEGACY_GRID_MS = 100;

	@Test
	public void pollIntervalStaysWithinBounds() {
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			int v = Global.nextPollIntervalMs();
			assertTrue("interval " + v + " below floor " + MIN_MS, v >= MIN_MS);
			assertTrue("interval " + v + " above ceiling " + MAX_MS, v <= MAX_MS);
		}
	}

	@Test
	public void pollIntervalIsSkewedAwayFromLegacy100msGrid() {
		int atLegacy = 0;
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			int v = Global.nextPollIntervalMs();
			seen.add(v);
			if (v == LEGACY_GRID_MS) atLegacy++;
		}
		assertTrue("expected a wide spread of poll intervals; saw only " + seen.size() + " distinct values",
				seen.size() >= DISTINCT_VALUES_MIN);
		double legacyFraction = atLegacy / (double) SAMPLE_SIZE;
		assertTrue("distribution should not peak at the legacy 100ms grid — fraction was " + legacyFraction,
				legacyFraction < 0.05);
	}

	@Test
	public void tickJitterStaysAroundNominalTick() {
		int trials = 10_000;
		int[] samples = new int[trials];
		long sum = 0;
		for (int i = 0; i < trials; i++) {
			samples[i] = Global.nextTickJitterMs(2);
			sum += samples[i];
		}
		double mean = sum / (double) trials;
		int nominal = 1200;
		int min = 1200 - 3 * 80;
		int max = 1200 + 3 * 80;
		assertTrue("tickJitter(2) mean " + mean + " should sit close to nominal " + nominal,
				Math.abs(mean - nominal) < 20);
		for (int v : samples) {
			assertTrue(v + " should not undercut floor " + min, v >= min);
			assertTrue(v + " should not exceed ceiling " + max, v <= max);
		}
	}

	@Test
	public void tickJitterBreaksThe600msGrid() {
		int trials = 5_000;
		int exact = 0;
		for (int i = 0; i < trials; i++) {
			if (Global.nextTickJitterMs(1) == 600) exact++;
		}
		double rate = exact / (double) trials;
		assertTrue("exactly 600ms rate " + rate + " too high; tick jitter must break grid alignment",
				rate < 0.05);
	}

	@Test
	public void pollIntervalMedianIsShiftedOffLegacy100ms() {
		int[] samples = new int[SAMPLE_SIZE];
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			samples[i] = Global.nextPollIntervalMs();
		}
		java.util.Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		assertTrue("median must not coincide with the legacy 100ms peak; got " + median,
				Math.abs(median - LEGACY_GRID_MS) >= 10);
		assertTrue("median should still fall inside the configured bounds; got " + median,
				median >= MIN_MS && median <= MAX_MS);
	}
}
