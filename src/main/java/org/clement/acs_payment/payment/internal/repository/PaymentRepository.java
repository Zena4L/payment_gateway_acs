package org.clement.acs_payment.payment.internal.repository;

import java.util.Optional;
import java.util.UUID;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByReference(String reference);
}
