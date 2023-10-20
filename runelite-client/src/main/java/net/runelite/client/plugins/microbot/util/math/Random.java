package net.runelite.client.plugins.microbot.util.math;

public class Random {
    /**
     * Generates a random integer within the specified range [min, max].
     *
     * @param min The minimum value (inclusive) of the range within which the random integer should be generated.
     * @param max The maximum value (inclusive) of the range within which the random integer should be generated.
     * @return A random integer within the specified range [min, max].
     */
    public static int random(final int min, final int max) {
        final int n = Math.abs(max - min);
        return Math.min(min, max) + (n == 0 ? 0 : new java.util.Random().nextInt(n));
    }
    public static double randomDouble(final int min, final int max) {
        return  min + (max - min) * new java.util.Random().nextDouble();
    }
}