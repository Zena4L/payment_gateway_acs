package org.clement.acs_payment.payment.internal.service;

import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.ProviderDetails;
import org.clement.acs_payment.payment.internal.model.TransitionResult;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.webhook.events.ProcessingOutcome;
import org.clement.acs_payment.webhook.events.WebhookEventProcessed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderOutcomeWriter {

    private static final String NOT_FOUND_AT_PROVIDER = "Transaction not found at provider";

    private final PaymentRepository payments;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Transactional
    public Payment apply(UUID paymentId, VerificationResult result, TransitionTrigger trigger) {
        var payment = payments.findById(paymentId).orElseThrow();
        applyResult(payment, result, trigger);
        return payments.save(payment);
    }

    @Transactional
    public void applyFromWebhook(UUID paymentId, VerificationResult result, UUID webhookEventId) {
        var payment = payments.findById(paymentId).orElseThrow();
        var previous = payment.getStatus();
        var transition = applyResult(payment, result, TransitionTrigger.WEBHOOK);
        var saved = payments.save(payment);
        if (transition == TransitionResult.REJECTED) {
            report(webhookEventId, ProcessingOutcome.REJECTED, "Payment is " + previous + "; provider result not applied");
        } else {
            report(webhookEventId, ProcessingOutcome.PROCESSED, "Payment is " + saved.getStatus() + " (was " + previous + ")");
        }
    }

    @Transactional
    public void flagForReviewFromWebhook(UUID paymentId, UUID webhookEventId, String reason) {
        var payment = payments.findById(paymentId).orElseThrow();
        var transition = payment.flagForReview(TransitionTrigger.WEBHOOK, clock);
        var saved = payments.save(payment);
        if (transition == TransitionResult.REJECTED) {
            report(webhookEventId, ProcessingOutcome.REJECTED, "Payment is " + saved.getStatus() + "; event not applied");
        } else {
            report(webhookEventId, ProcessingOutcome.PROCESSED, "Moved to REQUIRES_REVIEW: " + reason);
        }
    }

    @Transactional
    public void reportOutcome(UUID webhookEventId, ProcessingOutcome outcome, String diagnostic) {
        report(webhookEventId, outcome, diagnostic);
    }

    private void report(UUID webhookEventId, ProcessingOutcome outcome, String diagnostic) {
        publisher.publishEvent(new WebhookEventProcessed(webhookEventId, outcome, diagnostic));
    }

    private TransitionResult applyResult(Payment payment, VerificationResult result, TransitionTrigger trigger) {
        return switch (result) {
            case VerificationResult.Found(var transaction) -> applyTransaction(payment, transaction, trigger);
            case VerificationResult.NotFound() -> applyNotFound(payment, trigger);
            case VerificationResult.Rejected(_), VerificationResult.Unavailable() ->
                    throw new IllegalArgumentException("Only Found or NotFound results can be applied");
        };
    }

    private TransitionResult applyTransaction(Payment payment, ProviderTransaction transaction, TransitionTrigger trigger) {
        if (!payment.matchesProviderRecord(transaction.reference(), transaction.amountMinor(), transaction.currency())) {
            log.warn("Provider data mismatch for reference {}: expected reference={} amountMinor={} currency={}, actual reference={} amountMinor={} currency={}",
                    payment.getReference(),
                    payment.getReference(), payment.getAmountMinor(), payment.getCurrency().getCurrencyCode(),
                    transaction.reference(), transaction.amountMinor(), transaction.currency());
            return payment.flagForReview(trigger, clock);
        }
        return switch (transaction.status()) {
            case SUCCESS -> payment.markSucceeded(details(transaction), trigger, clock);
            case FAILED -> payment.markFailed(details(transaction), trigger, clock);
            case PENDING -> payment.transitionTo(PaymentStatus.PENDING, trigger, clock);
            case REVERSED -> {
                log.warn("Provider reports reversed transaction for reference {}", payment.getReference());
                yield payment.flagForReview(trigger, clock);
            }
            case UNRECOGNIZED -> {
                log.warn("Leaving payment {} unchanged: unrecognised provider status '{}'",
                        payment.getReference(), transaction.rawStatus());
                yield TransitionResult.NO_OP;
            }
        };
    }

    private TransitionResult applyNotFound(Payment payment, TransitionTrigger trigger) {
        if (payment.getStatus() == PaymentStatus.UNKNOWN) {
            return payment.markFailed(NOT_FOUND_AT_PROVIDER, trigger, clock);
        }
        log.warn("Provider has no transaction for reference {} in status {}; leaving it unchanged",
                payment.getReference(), payment.getStatus());
        return TransitionResult.NO_OP;
    }

    private static ProviderDetails details(ProviderTransaction transaction) {
        return ProviderDetails.builder()
                .transactionId(transaction.id())
                .channel(transaction.channel())
                .paidAt(transaction.paidAt())
                .gatewayResponse(transaction.gatewayResponse())
                .build();
    }
}
