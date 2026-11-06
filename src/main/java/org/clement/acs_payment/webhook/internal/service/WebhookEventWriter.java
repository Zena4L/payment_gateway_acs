package org.clement.acs_payment.webhook.internal.service;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.paystack.WebhookPayload;
import org.clement.acs_payment.webhook.events.PaystackChargeReceived;
import org.clement.acs_payment.webhook.internal.model.WebhookEvent;
import org.clement.acs_payment.webhook.internal.repository.WebhookEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookEventWriter {

    private final WebhookEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Transactional
    public void recordReceived(WebhookPayload.ChargeSuccess charge) {
        var event = events.saveAndFlush(WebhookEvent.received(charge, clock));
        publisher.publishEvent(PaystackChargeReceived.builder()
                .webhookEventId(event.getId())
                .dedupKey(WebhookEvent.dedupKey(charge))
                .reference(charge.reference())
                .providerTransactionId(charge.transactionId())
                .amountMinor(charge.amountMinor())
                .currency(charge.currency())
                .receivedAt(event.getReceivedAt())
                .build());
    }

    @Transactional
    public void recordDuplicate(WebhookPayload.ChargeSuccess charge) {
        events.save(WebhookEvent.duplicate(charge, clock));
    }

    @Transactional
    public void recordIgnored(WebhookPayload.Unhandled unhandled) {
        events.save(WebhookEvent.ignored(unhandled, clock));
    }

    @Transactional
    public void recordRejected(WebhookPayload.Malformed malformed) {
        events.save(WebhookEvent.rejected(malformed, clock));
    }
}
