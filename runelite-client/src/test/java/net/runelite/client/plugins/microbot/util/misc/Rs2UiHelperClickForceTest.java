package net.runelite.client.plugins.microbot.util.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Rs2UiHelperClickForceTest {

	private static final double MIN = 0.55;
	private static final double MAX = 0.95;
	private static final double LEGACY_FIXED = 0.78;

	@Test
	public void sessionClickForceIsWithinConfiguredRange() {
		double f = Rs2UiHelper.getSessionClickForce();
		assertTrue("force " + f + " below min " + MIN, f >= MIN);
		assertTrue("force " + f + " above max " + MAX, f <= MAX);
	}

	@Test
	public void sessionClickForceIsStableWithinSession() {
		double first = Rs2UiHelper.getSessionClickForce();
		for (int i = 0; i < 100; i++) {
			assertEquals(first, Rs2UiHelper.getSessionClickForce(), 0.0);
		}
	}

	@Test
	public void sessionClickForceIsNotPinnedAtLegacyConstant() {
		double f = Rs2UiHelper.getSessionClickForce();
		assertTrue("the first draw of the session accidentally matched the legacy 0.78; odds alone = 0 unless the RNG is broken",
				Math.abs(f - LEGACY_FIXED) > 0.0001 || f != LEGACY_FIXED);
	}
}
