package org.clement.acs_payment.payment.internal.repository;

import java.util.Optional;
import java.util.UUID;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByReference(String reference);
}
