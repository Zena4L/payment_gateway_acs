package org.clement.acs_payment.payment.internal.repository;

import java.util.List;
import java.util.UUID;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransitionRepository extends JpaRepository<PaymentTransition, UUID> {

    List<PaymentTransition> findByPaymentIdOrderByOccurredAtAsc(UUID paymentId);
}
