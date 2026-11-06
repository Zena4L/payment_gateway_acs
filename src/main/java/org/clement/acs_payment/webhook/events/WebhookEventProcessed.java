package org.clement.acs_payment.webhook.events;

import java.util.UUID;

public record WebhookEventProcessed(UUID webhookEventId, ProcessingOutcome outcome, String diagnostic) {
}
