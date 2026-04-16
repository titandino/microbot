package net.runelite.client.plugins.microbot.statemachine;

/**
 * Describes a transition that could fire from the current state, along with
 * whether its guard condition is currently satisfied. Used in debug snapshots.
 *
 * @param <S> the state enum type
 */
public final class PendingTransition<S extends Enum<S>> {

    private final S to;
    private final String conditionExpression;
    private final boolean currentlyTrue;
    private final String reason;

    public PendingTransition(S to, String conditionExpression, boolean currentlyTrue, String reason) {
        this.to = to;
        this.conditionExpression = conditionExpression;
        this.currentlyTrue = currentlyTrue;
        this.reason = reason;
    }

    public S to() { return to; }
    public String conditionExpression() { return conditionExpression; }
    public boolean isCurrentlyTrue() { return currentlyTrue; }
    public String reason() { return reason; }
}
