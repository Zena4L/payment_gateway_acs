package org.clement.acs_payment.payment.internal.service;

import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.ProviderDetails;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.VerificationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderOutcomeWriter {

    private static final String NOT_FOUND_AT_PROVIDER = "Transaction not found at provider";

    private final PaymentRepository payments;
    private final Clock clock;

    @Transactional
    public Payment apply(UUID paymentId, VerificationResult result, TransitionTrigger trigger) {
        var payment = payments.findById(paymentId).orElseThrow();
        switch (result) {
            case VerificationResult.Found(var transaction) -> applyTransaction(payment, transaction, trigger);
            case VerificationResult.NotFound() -> applyNotFound(payment, trigger);
            case VerificationResult.Rejected(_), VerificationResult.Unavailable() ->
                    throw new IllegalArgumentException("Only Found or NotFound results can be applied");
        }
        return payments.save(payment);
    }

    private void applyTransaction(Payment payment, ProviderTransaction transaction, TransitionTrigger trigger) {
        if (!payment.matchesProviderRecord(transaction.reference(), transaction.amountMinor(), transaction.currency())) {
            log.warn("Provider data mismatch for reference {}: expected reference={} amountMinor={} currency={}, actual reference={} amountMinor={} currency={}",
                    payment.getReference(),
                    payment.getReference(), payment.getAmountMinor(), payment.getCurrency().getCurrencyCode(),
                    transaction.reference(), transaction.amountMinor(), transaction.currency());
            payment.flagForReview(trigger, clock);
            return;
        }
        switch (transaction.status()) {
            case SUCCESS -> payment.markSucceeded(details(transaction), trigger, clock);
            case FAILED -> payment.markFailed(details(transaction), trigger, clock);
            case PENDING -> payment.transitionTo(PaymentStatus.PENDING, trigger, clock);
            case REVERSED -> {
                log.warn("Provider reports reversed transaction for reference {}", payment.getReference());
                payment.flagForReview(trigger, clock);
            }
            case UNRECOGNIZED -> log.warn("Leaving payment {} unchanged: unrecognised provider status '{}'",
                    payment.getReference(), transaction.rawStatus());
        }
    }

    private void applyNotFound(Payment payment, TransitionTrigger trigger) {
        if (payment.getStatus() == PaymentStatus.UNKNOWN) {
            payment.markFailed(NOT_FOUND_AT_PROVIDER, trigger, clock);
            return;
        }
        log.warn("Provider has no transaction for reference {} in status {}; leaving it unchanged",
                payment.getReference(), payment.getStatus());
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
