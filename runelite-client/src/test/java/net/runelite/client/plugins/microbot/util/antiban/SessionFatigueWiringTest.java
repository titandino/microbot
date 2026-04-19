package net.runelite.client.plugins.microbot.util.antiban;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the lifecycle we rely on from {@code Script.run()} and the break handler.
 * A stale "session active" flag means fatigue would persist through a break,
 * which defeats the point of the reset.
 */
public class SessionFatigueWiringTest {

	@After
	public void tearDown() {
		SessionFatigue.endSession();
	}

	@Test
	public void startSessionActivatesMultiplier() {
		SessionFatigue.endSession();
		assertFalse(SessionFatigue.isActive());
		SessionFatigue.startSession();
		assertTrue(SessionFatigue.isActive());
	}

	@Test
	public void endSessionResetsMultiplierToOne() {
		SessionFatigue.startSession();
		SessionFatigue.endSession();
		assertFalse(SessionFatigue.isActive());
		assertEquals(1.0, SessionFatigue.multiplier(), 1e-9);
		assertEquals(500, SessionFatigue.applyTo(500));
	}

	@Test
	public void applyToWithoutSessionIsIdentity() {
		SessionFatigue.endSession();
		assertEquals(250, SessionFatigue.applyTo(250));
		assertEquals(1_500, SessionFatigue.applyTo(1_500));
	}

	@Test
	public void applyToClampsNonPositiveInputs() {
		SessionFatigue.startSession();
		assertEquals(0, SessionFatigue.applyTo(0));
		assertEquals(-5, SessionFatigue.applyTo(-5));
	}
}
