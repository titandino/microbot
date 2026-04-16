package net.runelite.client.plugins.microbot.statemachine;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Script;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for scripts that model their logic as an explicit state machine.
 * <p>
 * Subclasses define an enum of states, a set of guarded transitions, and an
 * action handler per state. The framework evaluates transitions each tick,
 * fires at most one, then invokes the action for the (possibly new) current state.
 * <p>
 * Thread safety: all mutable state lives on the script's scheduled-executor thread.
 * An immutable {@link StateSnapshot} is published atomically after each tick so the
 * agent-server debug endpoint can read it without locks.
 * <p>
 * Usage:
 * <pre>{@code
 * public class MyScript extends StateMachineScript<MyScript.State> {
 *     enum State { IDLE, WALKING, BANKING }
 *
 *     @Override protected State initialState() { return State.IDLE; }
 *
 *     @Override protected List<Transition<State>> defineTransitions() {
 *         return List.of(
 *             Transition.from(State.IDLE).when(() -> needsBank(), "needsBank()")
 *                       .because("Inventory full").goTo(State.BANKING),
 *             Transition.from(State.BANKING).when(() -> bankDone(), "bankDone()")
 *                       .because("Banking complete").goTo(State.IDLE)
 *         );
 *     }
 *
 *     @Override protected void onState(State state) {
 *         switch (state) {
 *             case IDLE -> findTarget();
 *             case WALKING -> walkToTarget();
 *             case BANKING -> doBank();
 *         }
 *     }
 * }
 * </pre>
 *
 * Then in your run(config) method, call {@link #step()} inside the scheduled lambda
 * instead of writing manual if/else logic.
 *
 * @param <S> the state enum type
 */
@Slf4j
public abstract class StateMachineScript<S extends Enum<S>> extends Script {

    // --- Global registry so the debug handler can find all active machines ---
    private static final ConcurrentHashMap<String, StateMachineScript<?>> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Returns an unmodifiable view of all currently registered state machine scripts.
     */
    public static Map<String, StateMachineScript<?>> getRegistry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    // --- Per-instance state (mutated only on the script thread) ---

    private S currentState;
    private S previousState;
    private String lastTransitionReason;
    private Instant lastTransitionAt;
    private Instant stateEnteredAt;
    private long loopCount;
    private long transitionCount;
    private List<Transition<S>> transitions;
    private boolean initialized;

    /** Bounded ring buffer of recent transitions for the trace log. */
    private static final int MAX_TRACE_SIZE = 50;
    private final ArrayDeque<StateSnapshot.TraceEntry<S>> traceBuffer = new ArrayDeque<>(MAX_TRACE_SIZE);

    /** Thread-safe snapshot for the debug endpoint. */
    private final AtomicReference<StateSnapshot<S>> snapshot = new AtomicReference<>();

    // --- Abstract methods for subclasses ---

    /**
     * @return the state the machine starts in
     */
    protected abstract S initialState();

    /**
     * Define all transitions for the state machine. Called once during initialization.
     * Use {@link Transition#from(Enum)} to build transitions fluently.
     *
     * @return an ordered list of transitions (evaluated top-to-bottom per tick)
     */
    protected abstract List<Transition<S>> defineTransitions();

    /**
     * Execute the action for the given state. Called once per tick after transitions
     * have been evaluated. May block (e.g. sleepUntil, walkTo) — the framework
     * tolerates this in v1.
     */
    protected abstract void onState(S state);

    // --- Optional hooks ---

    /**
     * Called when a transition fires, before {@link #onState}. Override for custom logging
     * or metrics. Default implementation logs at INFO level.
     */
    protected void onTransition(S from, S to, String reason) {
        log.info("[{}]  {} → {}  \"{}\"", getScriptName(), from, to, reason);
    }

    /**
     * Called when {@link #onState} throws an exception. Override to customize error handling.
     * Default implementation logs the error. Return the state to transition to, or null
     * to stay in the current state.
     */
    protected S onError(S state, Exception e) {
        log.error("[{}] Error in state {}: {}", getScriptName(), state, e.getMessage(), e);
        return null;
    }

    // --- Engine ---

    /**
     * Execute one tick of the state machine: evaluate transitions, fire at most one,
     * then call {@link #onState} for the current state.
     * <p>
     * Call this inside your {@code scheduleWithFixedDelay} lambda instead of writing
     * manual if/else logic. The base {@link Script#run()} guard is checked first.
     *
     * @return false if the base guard paused execution, true otherwise
     */
    public final boolean step() {
        if (!initialized) {
            initialize();
        }

        // Check the base Script guard (heartbeat, blocking events, pause, etc.)
        if (!super.run()) {
            return false;
        }

        loopCount++;

        // Evaluate transitions (first matching wins)
        for (Transition<S> t : transitions) {
            if (t.from() == currentState) {
                try {
                    if (t.condition().getAsBoolean()) {
                        previousState = currentState;
                        currentState = t.to();
                        lastTransitionReason = t.reason();
                        Instant now = Instant.now();
                        lastTransitionAt = now;
                        stateEnteredAt = now;
                        transitionCount++;

                        // Record trace
                        StateSnapshot.TraceEntry<S> entry =
                                new StateSnapshot.TraceEntry<>(previousState, currentState, lastTransitionReason, now);
                        if (traceBuffer.size() >= MAX_TRACE_SIZE) {
                            traceBuffer.pollFirst();
                        }
                        traceBuffer.addLast(entry);

                        onTransition(previousState, currentState, lastTransitionReason);
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[{}] Transition guard threw for {} → {}: {}",
                            getScriptName(), t.from(), t.to(), e.getMessage());
                }
            }
        }

        // Publish snapshot for the debug endpoint
        publishSnapshot();

        // Execute the current state's action
        try {
            onState(currentState);
        } catch (Exception e) {
            S errorState = onError(currentState, e);
            if (errorState != null && errorState != currentState) {
                previousState = currentState;
                currentState = errorState;
                lastTransitionReason = "Error: " + e.getMessage();
                lastTransitionAt = Instant.now();
                stateEnteredAt = Instant.now();
                transitionCount++;
                publishSnapshot();
            }
        }

        return true;
    }

    private void initialize() {
        currentState = initialState();
        stateEnteredAt = Instant.now();
        transitions = Collections.unmodifiableList(defineTransitions());
        initialized = true;
        REGISTRY.put(getScriptName(), this);
        log.info("[{}] State machine initialized in state {}", getScriptName(), currentState);
    }

    private void publishSnapshot() {
        List<PendingTransition<S>> pending = new ArrayList<>();
        for (Transition<S> t : transitions) {
            if (t.from() == currentState) {
                boolean satisfied;
                try {
                    satisfied = t.condition().getAsBoolean();
                } catch (Exception e) {
                    satisfied = false;
                }
                pending.add(new PendingTransition<>(
                        t.to(), t.conditionExpression(), satisfied, t.reason()));
            }
        }

        snapshot.set(new StateSnapshot<>(
                currentState, previousState, lastTransitionReason,
                lastTransitionAt, stateEnteredAt,
                loopCount, transitionCount,
                pending,
                new ArrayList<>(traceBuffer)
        ));
    }

    /**
     * Returns the latest snapshot for the debug endpoint.
     * Thread-safe: can be called from any thread.
     */
    public StateSnapshot<S> getSnapshot() {
        return snapshot.get();
    }

    /**
     * Returns the current state. Only reliable when called from the script thread.
     */
    public S getCurrentState() {
        return currentState;
    }

    /**
     * Forcibly set the current state. Use sparingly — primarily for tests or
     * external reset commands.
     */
    public void forceState(S newState, String reason) {
        previousState = currentState;
        currentState = newState;
        lastTransitionReason = "Forced: " + reason;
        lastTransitionAt = Instant.now();
        stateEnteredAt = Instant.now();
        transitionCount++;
        publishSnapshot();
        log.info("[{}] State forced: {} → {}  \"{}\"", getScriptName(), previousState, currentState, reason);
    }

    @Override
    public void shutdown() {
        REGISTRY.remove(getScriptName());
        super.shutdown();
    }

    private String getScriptName() {
        return this.getClass().getSimpleName();
    }
}
