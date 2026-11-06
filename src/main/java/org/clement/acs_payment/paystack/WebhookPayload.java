package org.clement.acs_payment.paystack;

public sealed interface WebhookPayload {

    record ChargeSuccess(String eventType, long transactionId, String reference, long amountMinor, String currency)
            implements WebhookPayload {
    }

    record Unhandled(String eventType) implements WebhookPayload {
    }

    record Malformed(String reason) implements WebhookPayload {
    }
}
