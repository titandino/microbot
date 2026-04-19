package net.runelite.client.plugins.microbot.statemachineexample;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Example script demonstrating the StateMachineScript framework with the Queryable API.
 * <p>
 * Cycles through states scanning nearby game entities using queryable caches:
 *   SCAN_NPCS → COOLDOWN → SCAN_OBJECTS → COOLDOWN → SCAN_GROUND_ITEMS → COOLDOWN → REPORT → ...
 * <p>
 * Enable the plugin, then query state via:
 * {@code GET /debug/snapshot?script=StateMachineExampleScript}
 */
@Slf4j
public class StateMachineExampleScript extends StateMachineScript<StateMachineExampleScript.State> {

    enum State {
        SCAN_NPCS,
        COOLDOWN_AFTER_NPCS,
        SCAN_OBJECTS,
        COOLDOWN_AFTER_OBJECTS,
        SCAN_GROUND_ITEMS,
        COOLDOWN_AFTER_ITEMS,
        REPORT,
        ERROR
    }

    private StateMachineExampleConfig config;
    private Instant cooldownStartedAt;
    private int cycleCount;

    // Scan results carried across states for the REPORT phase
    private int nearbyNpcCount;
    private String nearestNpcName;
    private int nearbyObjectCount;
    private String nearestObjectName;
    private int nearbyItemCount;
    private String nearestItemName;
    private boolean actionDone;

    @Override
    protected State initialState() {
        return State.SCAN_NPCS;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                // NPC scan → cooldown
                Transition.<State>from(State.SCAN_NPCS)
                        .when(() -> actionDone, "actionDone")
                        .because("NPC scan complete")
                        .goTo(State.COOLDOWN_AFTER_NPCS),

                // Cooldown → object scan
                Transition.<State>from(State.COOLDOWN_AFTER_NPCS)
                        .when(this::cooldownElapsed, "cooldownElapsed()")
                        .because("Cooldown expired, scanning objects next")
                        .goTo(State.SCAN_OBJECTS),

                // Object scan → cooldown
                Transition.<State>from(State.SCAN_OBJECTS)
                        .when(() -> actionDone, "actionDone")
                        .because("Object scan complete")
                        .goTo(State.COOLDOWN_AFTER_OBJECTS),

                // Cooldown → ground item scan
                Transition.<State>from(State.COOLDOWN_AFTER_OBJECTS)
                        .when(this::cooldownElapsed, "cooldownElapsed()")
                        .because("Cooldown expired, scanning ground items next")
                        .goTo(State.SCAN_GROUND_ITEMS),

                // Ground item scan → cooldown
                Transition.<State>from(State.SCAN_GROUND_ITEMS)
                        .when(() -> actionDone, "actionDone")
                        .because("Ground item scan complete")
                        .goTo(State.COOLDOWN_AFTER_ITEMS),

                // Cooldown → report
                Transition.<State>from(State.COOLDOWN_AFTER_ITEMS)
                        .when(this::cooldownElapsed, "cooldownElapsed()")
                        .because("Cooldown expired, generating report")
                        .goTo(State.REPORT),

                // Report → back to NPC scan (new cycle)
                Transition.<State>from(State.REPORT)
                        .when(() -> actionDone, "actionDone")
                        .because("Report complete, starting new cycle")
                        .goTo(State.SCAN_NPCS)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case SCAN_NPCS:
                doScanNpcs();
                actionDone = true;
                break;
            case SCAN_OBJECTS:
                doScanObjects();
                actionDone = true;
                break;
            case SCAN_GROUND_ITEMS:
                doScanGroundItems();
                actionDone = true;
                break;
            case REPORT:
                doReport();
                actionDone = true;
                break;
            case COOLDOWN_AFTER_NPCS:
            case COOLDOWN_AFTER_OBJECTS:
            case COOLDOWN_AFTER_ITEMS:
                // Cooldowns do nothing; transition guard handles timing
                break;
            case ERROR:
                log.warn("[StateMachineExample] In error state");
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        actionDone = false;
        // Reset cooldown timer when entering any cooldown state
        if (to.name().startsWith("COOLDOWN_")) {
            cooldownStartedAt = Instant.now();
        }
    }

    @Override
    protected State onError(State state, Exception e) {
        log.error("[StateMachineExample] Error in state {}: {}", state, e.getMessage(), e);
        return State.ERROR;
    }

    // --- State actions using Queryable API ---

    private void doScanNpcs() {
        if (!Microbot.isLoggedIn()) return;

        // Queryable API: scan nearby NPCs within 15 tiles
        List<Rs2NpcModel> npcs = Microbot.getRs2NpcCache().query()
                .within(15)
                .toList();

        nearbyNpcCount = npcs.size();

        // Queryable API: find the nearest NPC
        Rs2NpcModel nearest = Microbot.getRs2NpcCache().query()
                .within(15)
                .nearest();

        nearestNpcName = nearest != null ? nearest.getName() : "none";

        log.info("[StateMachineExample] NPC scan: {} nearby, nearest='{}'",
                nearbyNpcCount, nearestNpcName);
    }

    private void doScanObjects() {
        if (!Microbot.isLoggedIn()) return;

        // Queryable API: scan nearby tile objects within 10 tiles
        List<Rs2TileObjectModel> objects = Microbot.getRs2TileObjectCache().query()
                .within(10)
                .toList();

        nearbyObjectCount = objects.size();

        // Queryable API: find the nearest named object
        Rs2TileObjectModel nearest = Microbot.getRs2TileObjectCache().query()
                .where(obj -> obj.getName() != null && !obj.getName().equals("null"))
                .within(10)
                .nearest();

        nearestObjectName = nearest != null ? nearest.getName() : "none";

        log.info("[StateMachineExample] Object scan: {} nearby, nearest='{}'",
                nearbyObjectCount, nearestObjectName);
    }

    private void doScanGroundItems() {
        if (!Microbot.isLoggedIn()) return;

        // Queryable API: scan nearby ground items within 15 tiles
        List<Rs2TileItemModel> items = Microbot.getRs2TileItemCache().query()
                .within(15)
                .toList();

        nearbyItemCount = items.size();

        // Queryable API: find nearest lootable ground item
        Rs2TileItemModel nearest = Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .within(15)
                .nearest();

        nearestItemName = nearest != null ? nearest.getName() : "none";

        log.info("[StateMachineExample] Ground item scan: {} nearby, nearest lootable='{}'",
                nearbyItemCount, nearestItemName);
    }

    private void doReport() {
        if (!Microbot.isLoggedIn()) return;

        cycleCount++;

        // Player state (static utility — no queryable cache for local player yet)
        boolean isAnimating = Rs2Player.isAnimating();
        boolean isMoving = Rs2Player.isMoving();
        int inventoryCount = Rs2Inventory.count();

        log.info("[StateMachineExample] === Cycle #{} Report ===", cycleCount);
        log.info("[StateMachineExample]   NPCs: {} nearby, nearest='{}'", nearbyNpcCount, nearestNpcName);
        log.info("[StateMachineExample]   Objects: {} nearby, nearest='{}'", nearbyObjectCount, nearestObjectName);
        log.info("[StateMachineExample]   Ground items: {} nearby, nearest lootable='{}'", nearbyItemCount, nearestItemName);
        log.info("[StateMachineExample]   Player: animating={}, moving={}, inventory={} items",
                isAnimating, isMoving, inventoryCount);
    }

    // --- Helpers ---

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
        log.info("[StateMachineExample] Starting state machine example script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Exception ex) {
                log.error("[StateMachineExample] Unexpected error in scheduled loop", ex);
            }
        }, 0, config.tickDelay(), TimeUnit.MILLISECONDS);

        return true;
    }
}
