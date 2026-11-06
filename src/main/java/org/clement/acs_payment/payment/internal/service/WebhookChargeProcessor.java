package org.clement.acs_payment.payment.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.webhook.events.PaystackChargeReceived;
import org.clement.acs_payment.webhook.events.ProcessingOutcome;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookChargeProcessor {

    private final PaymentRepository payments;
    private final PaystackGateway paystack;
    private final ProviderOutcomeWriter outcomes;

    public void process(PaystackChargeReceived event) {
        var found = payments.findByReference(event.reference());
        if (found.isEmpty()) {
            log.warn("Webhook {} refers to unknown reference {}", event.dedupKey(), event.reference());
            outcomes.reportOutcome(event.webhookEventId(), ProcessingOutcome.IGNORED, "No payment with this reference");
            return;
        }
        var payment = found.get();
        if (payment.getStatus().isFinal() || payment.getStatus() == PaymentStatus.REQUIRES_REVIEW) {
            reportSettled(event, payment);
            return;
        }
        if (!payment.matchesAmount(event.amountMinor(), event.currency())) {
            log.warn("Webhook {} amount/currency mismatch for reference {}: expected amountMinor={} currency={}, actual amountMinor={} currency={}",
                    event.dedupKey(), payment.getReference(), payment.getAmountMinor(), payment.getCurrency().getCurrencyCode(),
                    event.amountMinor(), event.currency());
            outcomes.flagForReviewFromWebhook(payment.getId(), event.webhookEventId(), "amount/currency mismatch");
            return;
        }
        var result = paystack.verify(payment.getReference());
        switch (result) {
            case VerificationResult.Unavailable(), VerificationResult.Rejected(_) -> outcomes.reportOutcome(
                    event.webhookEventId(), ProcessingOutcome.FAILED, "Could not confirm with provider; payment left unchanged");
            case VerificationResult.Found(_), VerificationResult.NotFound() -> OptimisticLocking.retryOnce(
                    () -> outcomes.applyFromWebhook(payment.getId(), result, event.webhookEventId()));
        }
    }

    private void reportSettled(PaystackChargeReceived event, Payment payment) {
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            outcomes.reportOutcome(event.webhookEventId(), ProcessingOutcome.PROCESSED, "Payment already SUCCESS");
            return;
        }
        log.warn("Webhook {} not applied: payment {} is {}", event.dedupKey(), payment.getReference(), payment.getStatus());
        outcomes.reportOutcome(event.webhookEventId(), ProcessingOutcome.REJECTED, "Payment is " + payment.getStatus() + "; event not applied");
    }
}
