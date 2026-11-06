package org.clement.acs_payment.webhook.internal.service;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.webhook.events.WebhookEventProcessed;
import org.clement.acs_payment.webhook.internal.repository.WebhookEventRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class WebhookOutcomeRecorder {

    private final WebhookEventRepository events;
    private final Clock clock;

    @ApplicationModuleListener
    void on(WebhookEventProcessed processed) {
        var event = events.findById(processed.webhookEventId()).orElseThrow();
        event.recordOutcome(processed.outcome(), processed.diagnostic(), clock);
        log.info("Webhook event {} {}: {}", event.getDedupKey(), processed.outcome(), processed.diagnostic());
    }
}
