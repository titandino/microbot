package net.runelite.client.plugins.microbot.util.mouse;

public final class BotEventGuard {
	private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

	private BotEventGuard() {
	}

	public static void begin() {
		DEPTH.set(DEPTH.get() + 1);
	}

	public static void end() {
		int next = DEPTH.get() - 1;
		if (next <= 0) {
			DEPTH.remove();
		} else {
			DEPTH.set(next);
		}
	}

	public static boolean isSynthetic() {
		return DEPTH.get() > 0;
	}
}
