package net.runelite.client.plugins.microbot.statemachineexample;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Example script demonstrating the StateMachineScript framework.
 * <p>
 * Cycles through states:
 *   CHECK_INVENTORY → COOLDOWN_1 → CHECK_PLAYER → COOLDOWN_2 → CHECK_INVENTORY → ...
 * <p>
 * Each state logs information and transitions based on simple conditions.
 * Enable the plugin, then query state via:
 * {@code GET /debug/snapshot?script=StateMachineExampleScript}
 */
@Slf4j
public class StateMachineExampleScript extends StateMachineScript<StateMachineExampleScript.State> {

    enum State {
        CHECK_INVENTORY,
        COOLDOWN_AFTER_INVENTORY,
        CHECK_PLAYER,
        COOLDOWN_AFTER_PLAYER,
        ERROR
    }

    private StateMachineExampleConfig config;
    private Instant cooldownStartedAt;
    private int cycleCount;

    @Override
    protected State initialState() {
        return State.CHECK_INVENTORY;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.CHECK_INVENTORY)
                        .when(() -> true, "always (one-tick action)")
                        .because("Inventory check complete")
                        .goTo(State.COOLDOWN_AFTER_INVENTORY),

                Transition.<State>from(State.COOLDOWN_AFTER_INVENTORY)
                        .when(() -> cooldownElapsed(), "cooldownElapsed()")
                        .because("Cooldown expired, checking player next")
                        .goTo(State.CHECK_PLAYER),

                Transition.<State>from(State.CHECK_PLAYER)
                        .when(() -> true, "always (one-tick action)")
                        .because("Player check complete")
                        .goTo(State.COOLDOWN_AFTER_PLAYER),

                Transition.<State>from(State.COOLDOWN_AFTER_PLAYER)
                        .when(() -> cooldownElapsed(), "cooldownElapsed()")
                        .because("Cooldown expired, starting new cycle")
                        .goTo(State.CHECK_INVENTORY)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case CHECK_INVENTORY:
                doCheckInventory();
                break;
            case CHECK_PLAYER:
                doCheckPlayer();
                break;
            case COOLDOWN_AFTER_INVENTORY:
            case COOLDOWN_AFTER_PLAYER:
                // Cooldowns do nothing; transition guard handles timing
                break;
            case ERROR:
                log.warn("[Example] In error state, waiting for manual intervention");
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (to == State.COOLDOWN_AFTER_INVENTORY || to == State.COOLDOWN_AFTER_PLAYER) {
            cooldownStartedAt = Instant.now();
        }
    }

    @Override
    protected State onError(State state, Exception e) {
        log.error("[Example] Error in state {}: {}", state, e.getMessage(), e);
        return State.ERROR;
    }

    // --- State actions ---

    private void doCheckInventory() {
        if (!Microbot.isLoggedIn()) return;

        int itemCount = Rs2Inventory.count();
        int emptySlots = Rs2Inventory.getEmptySlots();
        boolean isFull = Rs2Inventory.isFull();

        log.info("[Example] Inventory: {} items, {} empty slots, full={}", itemCount, emptySlots, isFull);
    }

    private void doCheckPlayer() {
        if (!Microbot.isLoggedIn()) return;

        boolean isAnimating = Rs2Player.isAnimating();
        boolean isMoving = Rs2Player.isMoving();

        log.info("[Example] Player: animating={}, moving={}", isAnimating, isMoving);

        cycleCount++;
        log.info("[Example] Completed cycle #{}", cycleCount);
    }

    private boolean cooldownElapsed() {
        return cooldownStartedAt != null &&
                System.currentTimeMillis() - cooldownStartedAt.toEpochMilli() > getIdleDuration();
    }

    private int getIdleDuration() {
        return config != null ? config.idleDuration() : 3000;
    }

    // --- Lifecycle ---

    public boolean run(StateMachineExampleConfig config) {
        this.config = config;
        log.info("[Example] Starting StateMachine example script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Exception ex) {
                log.error("[Example] Unexpected error in scheduled loop", ex);
            }
        }, 0, config.tickDelay(), TimeUnit.MILLISECONDS);

        return true;
    }
}
