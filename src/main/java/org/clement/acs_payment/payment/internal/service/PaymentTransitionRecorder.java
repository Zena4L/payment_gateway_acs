package org.clement.acs_payment.payment.internal.service;

import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.model.PaymentTransitioned;
import org.clement.acs_payment.payment.internal.repository.PaymentTransitionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentTransitionRecorder {

    private final PaymentTransitionRepository transitions;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(PaymentTransitioned event) {
        transitions.save(PaymentTransition.from(event));
    }
}
