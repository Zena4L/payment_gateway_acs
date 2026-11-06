package org.clement.acs_payment.paystack;

import java.time.Instant;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record ProviderTransaction(
        long id,
        ProviderStatus status,
        String rawStatus,
        String reference,
        long amountMinor,
        String currency,
        @Nullable String channel,
        @Nullable Instant paidAt,
        @Nullable String gatewayResponse) {
}
