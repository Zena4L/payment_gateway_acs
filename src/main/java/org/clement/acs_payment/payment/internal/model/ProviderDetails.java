package org.clement.acs_payment.payment.internal.model;

import java.time.Instant;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record ProviderDetails(
        long transactionId,
        @Nullable String channel,
        @Nullable Instant paidAt,
        @Nullable String gatewayResponse) {
}
