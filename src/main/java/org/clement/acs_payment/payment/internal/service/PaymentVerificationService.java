package org.clement.acs_payment.payment.internal.service;

import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.exception.PaymentNotFoundException;
import org.clement.acs_payment.payment.internal.exception.ProviderRejectedException;
import org.clement.acs_payment.payment.internal.exception.ProviderUnavailableException;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.security.ApiClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentVerificationService {

    private final PaymentRepository payments;
    private final PaystackGateway paystack;
    private final ProviderOutcomeWriter outcomes;

    public Payment verify(String reference, ApiClient caller) {
        var payment = payments.findByReference(reference)
                .filter(found -> caller.isAdmin() || found.getOwnerId().equals(caller.ownerId()))
                .orElseThrow(PaymentNotFoundException::new);
        if (payment.getStatus().isFinal() || payment.getStatus() == PaymentStatus.REQUIRES_REVIEW) {
            return payment;
        }
        var result = paystack.verify(reference);
        return switch (result) {
            case VerificationResult.Unavailable() -> throw ProviderUnavailableException.duringVerification();
            case VerificationResult.Rejected(_) -> throw new ProviderRejectedException();
            case VerificationResult.Found(_), VerificationResult.NotFound() -> apply(payment, result);
        };
    }

    private Payment apply(Payment payment, VerificationResult result) {
        try {
            return outcomes.apply(payment.getId(), result, TransitionTrigger.VERIFY);
        } catch (ObjectOptimisticLockingFailureException ex) {
            return outcomes.apply(payment.getId(), result, TransitionTrigger.VERIFY);
        }
    }
}
