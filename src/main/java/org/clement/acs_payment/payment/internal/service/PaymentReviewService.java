package org.clement.acs_payment.payment.internal.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.payment.internal.exception.PaymentNotFoundException;
import org.clement.acs_payment.payment.internal.model.Actor;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.payment.internal.repository.PaymentTransitionRepository;
import org.clement.acs_payment.security.ApiClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReviewService {

    private final PaymentWriter writer;
    private final PaymentRepository payments;
    private final PaymentTransitionRepository transitions;

    public Payment resolve(UUID paymentId, ResolutionOutcome outcome, String reason, ApiClient admin) {
        var resolved = OptimisticLocking.retryOnce(() -> writer.resolveReview(paymentId, outcome, new Actor(admin.ownerId(), admin.keyId()), reason));
        log.info("Payment {} review resolved to {} by {}", resolved.getReference(), outcome, admin.ownerId());
        return resolved;
    }

    @Transactional(readOnly = true)
    public List<PaymentTransition> transitions(UUID paymentId) {
        if (!payments.existsById(paymentId)) {
            throw new PaymentNotFoundException();
        }
        return transitions.findByPaymentIdOrderByOccurredAtAsc(paymentId);
    }
}
