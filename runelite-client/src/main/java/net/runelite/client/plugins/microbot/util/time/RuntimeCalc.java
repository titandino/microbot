package net.runelite.client.plugins.microbot.util.time;

public class RuntimeCalc {
    /**
     * Get a pretty string representing the time since startTimeMs
     * @param startTimeMs milliseconds to calculate the time from
     * @return a string that looks like ###ms when less than 1 second has elapsed, ##m ##s when less than 1 hour has elapsed, ##h ##m otherwise
     */
    public static String prettyRunTime(long startTimeMs) {
       long currentTime = System.currentTimeMillis();
        long elapsedTimeMs = currentTime - startTimeMs;

        // For less than 1 second
        if (elapsedTimeMs < 1000) {
            return elapsedTimeMs + "ms";
        }

        // For less than 1 hour
        if (elapsedTimeMs < 3600000) {
            long seconds = elapsedTimeMs / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;
            return minutes + "m " + seconds + "s";
        }

        // For 1 hour or more
        long minutes = elapsedTimeMs / 60000;
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }
}
