package org.clement.acs_payment.payment.internal.service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.config.PaymentProperties;
import org.clement.acs_payment.payment.internal.exception.DuplicatePaymentException;
import org.clement.acs_payment.payment.internal.exception.PaymentNotFoundException;
import org.clement.acs_payment.payment.internal.model.Actor;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.TransitionCause;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.InitializationResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWriter {

    private static final String PROVIDER_UNAVAILABLE = "Payment provider unavailable";
    private static final Set<PaymentStatus> CLOSED_UNPAID = EnumSet.of(PaymentStatus.FAILED, PaymentStatus.EXPIRED);

    private final PaymentRepository payments;
    private final PaymentProperties properties;
    private final Clock clock;

    @Transactional
    public Payment create(InitiatePaymentCommand command, Money money) {
        var payment = Payment.initialize(command.reference(), command.initiator(), command.email(), money, clock);
        rejectLiveDuplicate(payment);
        return payments.saveAndFlush(payment);
    }

    @Transactional
    public Payment resolveReview(UUID paymentId, ResolutionOutcome outcome, Actor admin, String reason) {
        var payment = payments.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
        payment.resolveReview(outcome.getStatus(), admin, reason, clock);
        return payments.save(payment);
    }

    @Transactional
    public Payment applyInitialization(UUID paymentId, InitializationResult result, Actor initiator) {
        var payment = payments.findById(paymentId).orElseThrow();
        var cause = TransitionCause.by(initiator, TransitionTrigger.INITIATE);
        switch (result) {
            case InitializationResult.Initialized(var authorizationUrl) ->
                    payment.markInitialized(authorizationUrl, initiator, clock);
            case InitializationResult.Rejected rejected when rejected.alreadyKnownToProvider() ->
                    payment.markOutcomeUnknown(initiator, clock);
            case InitializationResult.Rejected(_, var message) ->
                    payment.markFailed(message, cause, clock);
            case InitializationResult.Unavailable() ->
                    payment.markFailed(PROVIDER_UNAVAILABLE, cause, clock);
            case InitializationResult.OutcomeUnknown() ->
                    payment.markOutcomeUnknown(initiator, clock);
        }
        return payments.save(payment);
    }

    private void rejectLiveDuplicate(Payment payment) {
        var window = properties.duplicateWindow();
        if (window.isZero()) {
            return;
        }
        payments.lockIntent(payment.intentKey());
        payments.findLiveDuplicates(payment.getOwnerId(), payment.getEmail(), payment.getAmountMinor(), payment.getCurrency(),
                        payment.getReference(), payment.getCreatedAt().minus(window), CLOSED_UNPAID, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .ifPresent(existing -> {
                    throw new DuplicatePaymentException(existing.getReference(), existing.getId());
                });
    }
}
