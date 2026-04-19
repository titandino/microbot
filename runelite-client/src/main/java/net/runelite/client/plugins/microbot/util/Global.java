package net.runelite.client.plugins.microbot.util;

import lombok.SneakyThrows;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public class Global {
    static ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10);
    static ScheduledFuture<?> scheduledFuture;

    private static final int POLL_MIN_MS = 40;
    private static final int POLL_MAX_MS = 320;
    private static final double POLL_LOG_MEAN = 4.41;
    private static final double POLL_LOG_SIGMA = 0.22;

    static int nextPollIntervalMs() {
        double gaussian = ThreadLocalRandom.current().nextGaussian();
        double sample = Math.exp(POLL_LOG_MEAN + POLL_LOG_SIGMA * gaussian);
        if (sample < POLL_MIN_MS) return POLL_MIN_MS;
        if (sample > POLL_MAX_MS) return POLL_MAX_MS;
        return (int) sample;
    }

    public static ScheduledFuture<?> awaitExecutionUntil(Runnable callback, BooleanSupplier awaitedCondition, int time) {
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (awaitedCondition.getAsBoolean()) {
                scheduledFuture.cancel(true);
                scheduledFuture = null;
                callback.run();
            }
        }, 0, time, TimeUnit.MILLISECONDS);
        return scheduledFuture;
    }

    public static void sleep(int start) {
        if (Microbot.getClient().isClientThread()) return;
        try {
            Thread.sleep(start);
        } catch (InterruptedException ignored) {
            // ignore interrupted
        }
    }

    public static void sleep(int start, int end) {
        int randomSleep = Rs2Random.between(start, end);
        sleep(randomSleep);
    }

    public static void sleepGaussian(int mean, int stddev) {
        int randomSleep = Rs2Random.randomGaussian(mean, stddev);
        sleep(randomSleep);
    }

    private static final int TICK_MS = 600;
    private static final int TICK_JITTER_SIGMA_MS = 80;

    static int nextTickJitterMs(int ticks) {
        int base = Math.max(0, ticks * TICK_MS);
        double g = ThreadLocalRandom.current().nextGaussian();
        int sample = (int) Math.round(base + g * TICK_JITTER_SIGMA_MS);
        int floor = Math.max(100, base - 3 * TICK_JITTER_SIGMA_MS);
        int ceil = base + 3 * TICK_JITTER_SIGMA_MS;
        if (sample < floor) return floor;
        if (sample > ceil) return ceil;
        return sample;
    }

    public static void sleepTickJitter(int ticks) {
        sleep(nextTickJitterMs(ticks));
    }

    @SneakyThrows
    public static <T> T sleepUntilNotNull(Callable<T> method, int timeoutMillis, int sleepMillis) {
        if (Microbot.getClient().isClientThread()) return null;
        boolean done;
        T methodResponse;
        final long endTime = System.currentTimeMillis()+timeoutMillis;
        do {
            methodResponse = method.call();
            done = methodResponse != null;
            sleep(sleepMillis);
        } while (!done && System.currentTimeMillis() < endTime);
        return methodResponse;
    }

    public static <T> T sleepUntilNotNull(Callable<T> method, int timeoutMillis) {
        return sleepUntilNotNull(method, timeoutMillis, 100);
    }

    /**
     * Polls until the supplied condition becomes true or timeout elapses.
     * Must not be invoked on the client thread; callers should run on script/executor threads.
     */
    public static boolean sleepUntil(BooleanSupplier awaitedCondition) {
        return sleepUntil(awaitedCondition, 5000);
    }

    /**
     * Polls until the supplied condition becomes true or the given duration elapses.
     * No-op on the client thread to avoid blocking RuneLite.
     */
    public static boolean sleepUntil(BooleanSupplier awaitedCondition, int time) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            while (System.currentTimeMillis() - startTime < time) {
                if (awaitedCondition.getAsBoolean()) return true;
                sleep(nextPollIntervalMs());
            }
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntil(BooleanSupplier awaitedCondition, Runnable action, long timeoutMillis, int sleepMillis) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (System.nanoTime() - startTime < timeoutNanos) {
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
				action.run();
                sleep(sleepMillis);
            }
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntilTrue(BooleanSupplier awaitedCondition) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            do {
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
                sleep(nextPollIntervalMs());
            } while (System.currentTimeMillis() - startTime < 5000);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntilTrue(BooleanSupplier awaitedCondition, int time, int timeout) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            do {
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
                sleep(time);
            } while (System.currentTimeMillis() - startTime < timeout);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntilTrue(BooleanSupplier awaitedCondition, BooleanSupplier resetCondition, int time, int timeout) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            do {
                if (resetCondition.getAsBoolean()) {
                    startTime = System.currentTimeMillis();
                }
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
                sleep(time);
            } while (System.currentTimeMillis() - startTime < timeout);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    /**
     * Polls a condition on the client thread until true or timeout, without blocking the client thread itself.
     */
    public static void sleepUntilOnClientThread(BooleanSupplier awaitedCondition) {
        sleepUntilOnClientThread(awaitedCondition, Rs2Random.between(2500, 5000));
    }

    public static void sleepUntilOnClientThread(BooleanSupplier awaitedCondition, int time) {
        if (Microbot.getClient().isClientThread()) return;
        boolean done;
        long startTime = System.currentTimeMillis();
        try {
            do {
                done = Microbot.getClientThread().runOnClientThreadOptional(awaitedCondition::getAsBoolean).orElse(false);
            } while (!done && System.currentTimeMillis() - startTime < time);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
    }

    @Deprecated
    public boolean sleepUntilTick(int ticksToWait) {
        return sleepTicks(ticksToWait);
    }

    public static boolean sleepUntilNextTick() {
        if (Microbot.getClient().isClientThread()) return false;
        GameTickBroadcaster broadcaster = Microbot.getGameTickBroadcaster();
        if (broadcaster == null) return false;
        try {
            return broadcaster.awaitNextTick();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean sleepUntilNextTick(long timeoutMs) {
        if (Microbot.getClient().isClientThread()) return false;
        GameTickBroadcaster broadcaster = Microbot.getGameTickBroadcaster();
        if (broadcaster == null) return false;
        try {
            return broadcaster.awaitNextTick(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean sleepTicks(int ticks) {
        if (Microbot.getClient().isClientThread()) return false;
        for (int i = 0; i < ticks; i++) {
            if (!sleepUntilNextTick()) {
                return false;
            }
        }
        return true;
    }
}
