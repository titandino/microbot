package net.runelite.client.plugins.microbot.util.antiban;

public final class SessionFatigue {
	static final double HOURLY_SLOWDOWN = 0.04;
	static final double MAX_MULTIPLIER = 1.30;

	private static volatile long sessionStartNanos = 0L;

	private SessionFatigue() {
	}

	public static void startSession() {
		sessionStartNanos = System.nanoTime();
	}

	public static void endSession() {
		sessionStartNanos = 0L;
	}

	public static boolean isActive() {
		return sessionStartNanos != 0L;
	}

	public static double multiplier() {
		long start = sessionStartNanos;
		if (start == 0L) return 1.0;
		double elapsedMinutes = (System.nanoTime() - start) / 1_000_000_000.0 / 60.0;
		return computeMultiplier(elapsedMinutes);
	}

	static double computeMultiplier(double elapsedMinutes) {
		if (elapsedMinutes <= 0.0) return 1.0;
		double extra = Math.min(MAX_MULTIPLIER - 1.0, elapsedMinutes / 60.0 * HOURLY_SLOWDOWN);
		return 1.0 + extra;
	}

	public static int applyTo(int baseMs) {
		if (baseMs <= 0) return baseMs;
		return (int) Math.round(baseMs * multiplier());
	}
}
