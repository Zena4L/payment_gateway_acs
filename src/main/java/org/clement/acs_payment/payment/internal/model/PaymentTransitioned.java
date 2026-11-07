package org.clement.acs_payment.payment.internal.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record PaymentTransitioned(
        UUID paymentId,
        @Nullable PaymentStatus from,
        PaymentStatus to,
        TransitionTrigger trigger,
        Instant occurredAt,
        @Nullable String actor,
        @Nullable String actorKeyId,
        @Nullable String reason) {
}
