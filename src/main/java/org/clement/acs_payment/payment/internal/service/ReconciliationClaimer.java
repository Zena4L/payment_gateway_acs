package org.clement.acs_payment.payment.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReconciliationClaimer {

    private final PaymentRepository payments;

    @Transactional
    public List<UUID> claim(Instant staleBefore, int batchSize, Instant now) {
        var ids = payments.claimStaleIds(staleBefore, batchSize);
        if (!ids.isEmpty()) {
            payments.markReconciled(ids, now);
        }
        return ids;
    }
}
