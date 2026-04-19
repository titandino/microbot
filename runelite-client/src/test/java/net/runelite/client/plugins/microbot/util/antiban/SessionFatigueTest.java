package net.runelite.client.plugins.microbot.util.antiban;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionFatigueTest {

	@Before
	public void resetBefore() {
		SessionFatigue.endSession();
	}

	@After
	public void resetAfter() {
		SessionFatigue.endSession();
	}

	@Test
	public void multiplierIsOneWhenSessionNotStarted() {
		assertFalse(SessionFatigue.isActive());
		assertEquals(1.0, SessionFatigue.multiplier(), 1e-9);
		assertEquals(500, SessionFatigue.applyTo(500));
	}

	@Test
	public void computeMultiplierGrowsLinearlyUntilCap() {
		assertEquals(1.0, SessionFatigue.computeMultiplier(0), 1e-9);
		assertEquals(1.04, SessionFatigue.computeMultiplier(60), 1e-6);
		assertEquals(1.16, SessionFatigue.computeMultiplier(240), 1e-6);
		assertEquals(SessionFatigue.MAX_MULTIPLIER, SessionFatigue.computeMultiplier(450), 1e-6);
		assertEquals(SessionFatigue.MAX_MULTIPLIER, SessionFatigue.computeMultiplier(10_000), 1e-6);
	}

	@Test
	public void computeMultiplierIgnoresNegativeElapsed() {
		assertEquals(1.0, SessionFatigue.computeMultiplier(-5), 1e-9);
	}

	@Test
	public void startSessionActivatesState() {
		SessionFatigue.startSession();
		assertTrue(SessionFatigue.isActive());
		double m = SessionFatigue.multiplier();
		assertTrue("fresh session multiplier " + m + " should sit near 1.0", m >= 1.0 && m < 1.01);
	}

	@Test
	public void endSessionResets() {
		SessionFatigue.startSession();
		SessionFatigue.endSession();
		assertFalse(SessionFatigue.isActive());
		assertEquals(1.0, SessionFatigue.multiplier(), 1e-9);
	}

	@Test
	public void applyToScalesProportionally() {
		assertEquals(500, SessionFatigue.applyTo(500));
		assertEquals(0, SessionFatigue.applyTo(0));
		assertEquals(-3, SessionFatigue.applyTo(-3));
	}

	@Test
	public void capPreventsUnboundedDrift() {
		double cap = SessionFatigue.MAX_MULTIPLIER;
		for (double minutes = 400; minutes < 100_000; minutes += 500) {
			double m = SessionFatigue.computeMultiplier(minutes);
			assertTrue("multiplier " + m + " exceeded cap " + cap + " at " + minutes + " min", m <= cap + 1e-9);
		}
	}
}
