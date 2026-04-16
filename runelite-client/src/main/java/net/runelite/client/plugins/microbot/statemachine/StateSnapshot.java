package net.runelite.client.plugins.microbot.statemachine;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An immutable point-in-time snapshot of a state machine's runtime state.
 * Published atomically via {@link java.util.concurrent.atomic.AtomicReference}
 * so the HTTP debug handler can read it without locks.
 *
 * @param <S> the state enum type
 */
public final class StateSnapshot<S extends Enum<S>> {

    private final S currentState;
    private final S previousState;
    private final String lastTransitionReason;
    private final Instant lastTransitionAt;
    private final Instant stateEnteredAt;
    private final long loopCount;
    private final long transitionCount;
    private final List<PendingTransition<S>> pendingTransitions;
    private final List<TraceEntry<S>> recentTrace;

    public StateSnapshot(
            S currentState,
            S previousState,
            String lastTransitionReason,
            Instant lastTransitionAt,
            Instant stateEnteredAt,
            long loopCount,
            long transitionCount,
            List<PendingTransition<S>> pendingTransitions,
            List<TraceEntry<S>> recentTrace) {
        this.currentState = currentState;
        this.previousState = previousState;
        this.lastTransitionReason = lastTransitionReason;
        this.lastTransitionAt = lastTransitionAt;
        this.stateEnteredAt = stateEnteredAt;
        this.loopCount = loopCount;
        this.transitionCount = transitionCount;
        this.pendingTransitions = Collections.unmodifiableList(pendingTransitions);
        this.recentTrace = Collections.unmodifiableList(recentTrace);
    }

    public S currentState() { return currentState; }
    public S previousState() { return previousState; }
    public String lastTransitionReason() { return lastTransitionReason; }
    public Instant lastTransitionAt() { return lastTransitionAt; }
    public Instant stateEnteredAt() { return stateEnteredAt; }
    public long loopCount() { return loopCount; }
    public long transitionCount() { return transitionCount; }
    public List<PendingTransition<S>> pendingTransitions() { return pendingTransitions; }
    public List<TraceEntry<S>> recentTrace() { return recentTrace; }

    /**
     * Convert to a JSON-friendly map for the agent server debug endpoint.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("currentState", currentState != null ? currentState.name() : null);
        map.put("previousState", previousState != null ? previousState.name() : null);
        map.put("lastTransitionReason", lastTransitionReason);
        map.put("lastTransitionAt", lastTransitionAt != null ? lastTransitionAt.toString() : null);
        map.put("stateEnteredAt", stateEnteredAt != null ? stateEnteredAt.toString() : null);
        map.put("loopCount", loopCount);
        map.put("transitionCount", transitionCount);

        if (stateEnteredAt != null) {
            map.put("msInCurrentState", System.currentTimeMillis() - stateEnteredAt.toEpochMilli());
        }

        map.put("pendingTransitions", pendingTransitions.stream()
                .map(pt -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("to", pt.to().name());
                    m.put("condition", pt.conditionExpression());
                    m.put("currentlyTrue", pt.isCurrentlyTrue());
                    m.put("because", pt.reason());
                    return m;
                })
                .collect(Collectors.toList()));

        map.put("recentTrace", recentTrace.stream()
                .map(TraceEntry::toMap)
                .collect(Collectors.toList()));

        return map;
    }

    /**
     * A single entry in the state transition trace log.
     */
    public static final class TraceEntry<S extends Enum<S>> {
        private final S from;
        private final S to;
        private final String reason;
        private final Instant at;

        public TraceEntry(S from, S to, String reason, Instant at) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.at = at;
        }

        public S from() { return from; }
        public S to() { return to; }
        public String reason() { return reason; }
        public Instant at() { return at; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("from", from.name());
            map.put("to", to.name());
            map.put("reason", reason);
            map.put("at", at.toString());
            return map;
        }
    }
}
