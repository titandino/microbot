package net.runelite.client.plugins.microbot.statemachine;

import java.util.function.BooleanSupplier;

/**
 * An immutable transition definition for a state machine.
 * Describes a potential move from one state to another, guarded by a condition.
 *
 * @param <S> the state enum type
 */
public final class Transition<S extends Enum<S>> {

    private final S from;
    private final S to;
    private final BooleanSupplier condition;
    private final String reason;
    private final String conditionExpression;

    Transition(S from, S to, BooleanSupplier condition, String reason, String conditionExpression) {
        this.from = from;
        this.to = to;
        this.condition = condition;
        this.reason = reason;
        this.conditionExpression = conditionExpression;
    }

    public S from() { return from; }
    public S to() { return to; }
    public BooleanSupplier condition() { return condition; }
    public String reason() { return reason; }
    public String conditionExpression() { return conditionExpression; }

    /**
     * Start building a transition from the given state.
     */
    public static <S extends Enum<S>> FromBuilder<S> from(S state) {
        return new FromBuilder<>(state);
    }

    // --- Fluent builder stages ---

    public static final class FromBuilder<S extends Enum<S>> {
        private final S from;

        FromBuilder(S from) { this.from = from; }

        /**
         * Define the guard condition for this transition.
         * @param condition evaluated each tick; transition fires when true
         */
        public WhenBuilder<S> when(BooleanSupplier condition) {
            return new WhenBuilder<>(from, condition, null);
        }

        /**
         * Define the guard condition with a human-readable expression for debug output.
         */
        public WhenBuilder<S> when(BooleanSupplier condition, String conditionExpression) {
            return new WhenBuilder<>(from, condition, conditionExpression);
        }
    }

    public static final class WhenBuilder<S extends Enum<S>> {
        private final S from;
        private final BooleanSupplier condition;
        private final String conditionExpression;
        private String reason;

        WhenBuilder(S from, BooleanSupplier condition, String conditionExpression) {
            this.from = from;
            this.condition = condition;
            this.conditionExpression = conditionExpression;
        }

        /**
         * Human-readable reason why this transition fires (appears in debug output and trace logs).
         */
        public WhenBuilder<S> because(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Define the target state for this transition.
         */
        public Transition<S> goTo(S to) {
            return new Transition<>(from, to, condition,
                    reason != null ? reason : from + " → " + to,
                    conditionExpression);
        }
    }
}
