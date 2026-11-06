package org.clement.acs_payment.payment.internal.service;

import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.exception.PaymentNotFoundException;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.InitializationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWriter {

    private static final String PROVIDER_UNAVAILABLE = "Payment provider unavailable";

    private final PaymentRepository payments;
    private final Clock clock;

    @Transactional
    public Payment create(InitiatePaymentCommand command, Money money) {
        return payments.saveAndFlush(Payment.initialize(command.reference(), command.ownerId(), command.email(), money, clock));
    }

    @Transactional
    public Payment resolveReview(UUID paymentId, ResolutionOutcome outcome, String actor, String reason) {
        var payment = payments.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
        payment.resolveReview(outcome.getStatus(), actor, reason, clock);
        return payments.save(payment);
    }

    @Transactional
    public Payment applyInitialization(UUID paymentId, InitializationResult result) {
        var payment = payments.findById(paymentId).orElseThrow();
        switch (result) {
            case InitializationResult.Initialized(var authorizationUrl) ->
                    payment.markInitialized(authorizationUrl, clock);
            case InitializationResult.Rejected rejected when rejected.alreadyKnownToProvider() ->
                    payment.markOutcomeUnknown(clock);
            case InitializationResult.Rejected(_, var message) ->
                    payment.markFailed(message, TransitionTrigger.INITIATE, clock);
            case InitializationResult.Unavailable() ->
                    payment.markFailed(PROVIDER_UNAVAILABLE, TransitionTrigger.INITIATE, clock);
            case InitializationResult.OutcomeUnknown() ->
                    payment.markOutcomeUnknown(clock);
        }
        return payments.save(payment);
    }
}
