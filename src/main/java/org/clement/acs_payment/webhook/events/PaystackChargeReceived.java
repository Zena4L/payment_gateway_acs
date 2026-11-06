package org.clement.acs_payment.webhook.events;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PaystackChargeReceived(
        UUID webhookEventId,
        String dedupKey,
        String reference,
        long providerTransactionId,
        long amountMinor,
        String currency,
        Instant receivedAt) {
}
