package org.clement.acs_payment.payment.internal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByReference(String reference);

    @Query(value = "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:intentKey, 0))) as intent_lock",
            nativeQuery = true)
    long lockIntent(@Param("intentKey") String intentKey);

    @Query("""
            select p from Payment p
            where p.ownerId = :ownerId
              and p.email = :email
              and p.amountMinor = :amountMinor
              and p.currency = :currency
              and p.reference <> :reference
              and p.createdAt > :since
              and p.status not in :settled
            order by p.createdAt desc
            """)
    List<Payment> findLiveDuplicates(@Param("ownerId") String ownerId,
                                     @Param("email") String email,
                                     @Param("amountMinor") long amountMinor,
                                     @Param("currency") Currency currency,
                                     @Param("reference") String reference,
                                     @Param("since") Instant since,
                                     @Param("settled") Collection<PaymentStatus> settled,
                                     Pageable page);

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
