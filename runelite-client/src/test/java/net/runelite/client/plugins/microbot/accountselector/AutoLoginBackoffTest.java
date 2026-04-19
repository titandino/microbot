package net.runelite.client.plugins.microbot.accountselector;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AutoLoginBackoffTest {

	private static final int SAMPLES = 2_000;
	private static final int BASE = 5;

	@Test
	public void earlyRetriesStayInHumanScale() {
		int maxSecondAttempt = 0;
		for (int i = 0; i < SAMPLES; i++) {
			int v = AutoLoginScript.computeRetryBackoffSeconds(1, BASE);
			assertTrue("first retry should be at least base " + BASE + "; got " + v, v >= BASE);
			assertTrue("first retry should not exceed 10s (5s base + 30% jitter); got " + v, v <= 10);
			maxSecondAttempt = Math.max(maxSecondAttempt, v);
		}
	}

	@Test
	public void backoffEscalatesWithAttempts() {
		int meanAtAttempt1 = meanBackoff(1);
		int meanAtAttempt2 = meanBackoff(2);
		int meanAtAttempt3 = meanBackoff(3);
		int meanAtAttempt4 = meanBackoff(4);
		assertTrue("attempt 2 should delay longer than attempt 1 (got " + meanAtAttempt2 + " vs " + meanAtAttempt1 + ")",
				meanAtAttempt2 > meanAtAttempt1);
		assertTrue("attempt 3 should delay longer than attempt 2 (got " + meanAtAttempt3 + " vs " + meanAtAttempt2 + ")",
				meanAtAttempt3 > meanAtAttempt2);
		assertTrue("attempt 4 should delay longer than attempt 3 (got " + meanAtAttempt4 + " vs " + meanAtAttempt3 + ")",
				meanAtAttempt4 > meanAtAttempt3);
	}

	@Test
	public void backoffCapsAtMaxSchedule() {
		for (int attempt = 5; attempt <= 100; attempt++) {
			for (int i = 0; i < 50; i++) {
				int v = AutoLoginScript.computeRetryBackoffSeconds(attempt, BASE);
				assertTrue("backoff " + v + "s at attempt " + attempt + " should not exceed the 300s schedule top with 30% jitter",
						v <= 400);
			}
		}
	}

	@Test
	public void userConfiguredBaseActsAsFloor() {
		int highBase = 45;
		for (int i = 0; i < SAMPLES; i++) {
			int v = AutoLoginScript.computeRetryBackoffSeconds(1, highBase);
			assertTrue("configured base " + highBase + "s must be respected as a floor; got " + v,
					v >= highBase);
		}
	}

	private int meanBackoff(int attempt) {
		long sum = 0;
		for (int i = 0; i < SAMPLES; i++) {
			sum += AutoLoginScript.computeRetryBackoffSeconds(attempt, BASE);
		}
		return (int) (sum / SAMPLES);
	}
}
