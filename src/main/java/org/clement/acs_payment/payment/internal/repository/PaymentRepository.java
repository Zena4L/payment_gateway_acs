package org.clement.acs_payment.payment.internal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByReference(String reference);

    @Query(value = """
            select p.id from payment p
            where p.status in ('INITIALIZING', 'PENDING', 'UNKNOWN')
              and p.updated_at < :staleBefore
              and (p.last_reconciled_at is null or p.last_reconciled_at < :staleBefore)
            order by p.updated_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<UUID> claimStaleIds(@Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = "update payment set last_reconciled_at = :at where id in (:ids)", nativeQuery = true)
    void markReconciled(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);
}
