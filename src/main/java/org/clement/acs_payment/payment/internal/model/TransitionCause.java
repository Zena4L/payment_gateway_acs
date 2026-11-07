package org.clement.acs_payment.payment.internal.model;

import org.jspecify.annotations.Nullable;

public record TransitionCause(TransitionTrigger trigger, @Nullable Actor actor) {

    public static TransitionCause system(TransitionTrigger trigger) {
        return new TransitionCause(trigger, null);
    }

    public static TransitionCause by(Actor actor, TransitionTrigger trigger) {
        return new TransitionCause(trigger, actor);
    }
}
