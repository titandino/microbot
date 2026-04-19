package net.runelite.client.plugins.microbot.util.antiban.enums;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PlayStyleOrnsteinUhlenbeckTest {

	private static final int SAMPLES = 10_000;

	@Test
	public void stationaryAroundMean() {
		int mean = 10;
		int current = mean;
		double volatility = 2.0;
		long sum = 0;
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int i = 0; i < SAMPLES; i++) {
			current = PlayStyle.nextOrnsteinUhlenbeck(current, mean, volatility);
			sum += current;
			if (current < min) min = current;
			if (current > max) max = current;
		}
		double avg = sum / (double) SAMPLES;
		assertTrue("running mean " + avg + " drifted far from target " + mean,
				Math.abs(avg - mean) < 1.5);
		assertTrue("values must drift (min=" + min + ", max=" + max + ")", min < mean && max > mean);
	}

	@Test
	public void reversesTowardMeanFromAboveAndBelow() {
		int mean = 10;
		double volatility = 1.0;

		double meanFromAbove = averageOverNSteps(50, mean + 20, mean, volatility);
		double meanFromBelow = averageOverNSteps(50, Math.max(1, mean - 5), mean, volatility);
		assertTrue("starting far above mean should drift downward; got avg " + meanFromAbove,
				meanFromAbove < mean + 15);
		assertTrue("starting below mean should drift upward; got avg " + meanFromBelow,
				meanFromBelow > mean - 5);
	}

	@Test
	public void nextValueNeverZeroOrNegative() {
		int current = 1;
		for (int i = 0; i < SAMPLES; i++) {
			current = PlayStyle.nextOrnsteinUhlenbeck(current, 1, 5.0);
			assertTrue("value must stay >= 1 to keep tick intervals sane; got " + current, current >= 1);
		}
	}

	@Test
	public void noFixedPeriodSignature() {
		int mean = 15;
		double volatility = 2.0;
		int current = mean;
		int[] series = new int[2_000];
		for (int i = 0; i < series.length; i++) {
			current = PlayStyle.nextOrnsteinUhlenbeck(current, mean, volatility);
			series[i] = current;
		}
		double lag1Auto = autocorrelation(series, 1);
		double lag31Auto = autocorrelation(series, 31);
		assertTrue("lag-1 autocorrelation " + lag1Auto + " should stay well below 1.0 (OU mean-reverts quickly)",
				lag1Auto < 0.9);
		assertTrue("lag-31 autocorrelation " + lag31Auto + " should be small (no sine-wave period here)",
				Math.abs(lag31Auto) < 0.2);
	}

	private double averageOverNSteps(int steps, int startingValue, int mean, double volatility) {
		int current = startingValue;
		long sum = 0;
		for (int i = 0; i < steps; i++) {
			current = PlayStyle.nextOrnsteinUhlenbeck(current, mean, volatility);
			sum += current;
		}
		return sum / (double) steps;
	}

	private static double autocorrelation(int[] series, int lag) {
		double mean = 0;
		for (int v : series) mean += v;
		mean /= series.length;
		double num = 0;
		double denom = 0;
		for (int i = 0; i < series.length; i++) {
			double d = series[i] - mean;
			denom += d * d;
			if (i + lag < series.length) {
				num += d * (series[i + lag] - mean);
			}
		}
		return denom == 0 ? 0 : num / denom;
	}
}
