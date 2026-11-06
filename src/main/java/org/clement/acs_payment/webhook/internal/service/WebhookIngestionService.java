package org.clement.acs_payment.webhook.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.paystack.PaystackWebhooks;
import org.clement.acs_payment.paystack.WebhookPayload;
import org.clement.acs_payment.webhook.internal.exception.InvalidWebhookSignatureException;
import org.clement.acs_payment.webhook.internal.model.WebhookEvent;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIngestionService {

    private final PaystackWebhooks paystack;
    private final WebhookEventWriter writer;

    public void receive(byte[] body, @Nullable String signature) {
        if (!paystack.hasValidSignature(body, signature)) {
            log.warn("Rejected webhook with missing or invalid signature");
            throw new InvalidWebhookSignatureException();
        }
        switch (paystack.parse(body)) {
            case WebhookPayload.ChargeSuccess charge -> recordCharge(charge);
            case WebhookPayload.Unhandled unhandled -> {
                log.info("Ignoring unhandled webhook event type {}", unhandled.eventType());
                writer.recordIgnored(unhandled);
            }
            case WebhookPayload.Malformed malformed -> {
                log.warn("Rejecting malformed webhook: {}", malformed.reason());
                writer.recordRejected(malformed);
            }
        }
    }

    private void recordCharge(WebhookPayload.ChargeSuccess charge) {
        try {
            writer.recordReceived(charge);
        } catch (DataIntegrityViolationException ex) {
            log.info("Duplicate webhook {} for reference {}", WebhookEvent.dedupKey(charge), charge.reference());
            writer.recordDuplicate(charge);
        }
    }
}
