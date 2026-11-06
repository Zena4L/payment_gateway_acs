package org.clement.acs_payment.payment.internal.dto;

import java.time.Instant;
import lombok.Builder;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.jspecify.annotations.Nullable;

@Builder
public record TransitionResponse(
        @Nullable PaymentStatus from,
        PaymentStatus to,
        TransitionTrigger trigger,
        @Nullable String actor,
        @Nullable String reason,
        Instant occurredAt) {

    public static TransitionResponse from(PaymentTransition transition) {
        return TransitionResponse.builder()
                .from(transition.getFromStatus())
                .to(transition.getToStatus())
                .trigger(transition.getTriggeredBy())
                .actor(transition.getActor())
                .reason(transition.getReason())
                .occurredAt(transition.getOccurredAt())
                .build();
    }
}
