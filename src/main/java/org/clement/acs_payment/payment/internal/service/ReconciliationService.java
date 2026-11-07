package org.clement.acs_payment.payment.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.payment.PaymentReconciliation;
import org.clement.acs_payment.payment.ReconciliationReport;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.TransitionCause;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.VerificationResult;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService implements PaymentReconciliation {

    private final ReconciliationClaimer claimer;
    private final PaymentRepository payments;
    private final PaystackGateway paystack;
    private final ProviderOutcomeWriter outcomes;
    private final Clock clock;

    @Override
    public ReconciliationReport reconcile(Duration staleThreshold, Duration expiryCutoff, int batchSize) {
        var now = clock.instant();
        var claimed = claimer.claim(now.minus(staleThreshold), batchSize, now);
        var overdueBefore = now.minus(expiryCutoff);
        int resolved = 0;
        int expired = 0;
        int unavailable = 0;
        for (var paymentId : claimed) {
            var outcome = reconcileOne(paymentId, overdueBefore);
            if (outcome == Outcome.UNAVAILABLE) {
                unavailable++;
                break;
            }
            if (outcome == Outcome.RESOLVED) {
                resolved++;
            }
            if (outcome == Outcome.EXPIRED) {
                expired++;
            }
        }
        var report = new ReconciliationReport(claimed.size(), resolved, expired, unavailable);
        log.info("Reconciliation run: claimed={} resolved={} expired={} unavailable={}",
                report.claimed(), report.resolved(), report.expired(), report.unavailable());
        return report;
    }

    private Outcome reconcileOne(UUID paymentId, Instant overdueBefore) {
        var payment = payments.findById(paymentId).orElseThrow();
        var before = payment.getStatus();
        if (before.isFinal() || before == PaymentStatus.REQUIRES_REVIEW) {
            return Outcome.UNCHANGED;
        }
        var result = paystack.verify(payment.getReference());
        Payment updated;
        switch (result) {
            case VerificationResult.Unavailable(), VerificationResult.Rejected(_) -> {
                log.warn("Reconciliation could not verify reference {}; stopping this run", payment.getReference());
                return Outcome.UNAVAILABLE;
            }
            case VerificationResult.NotFound() when before == PaymentStatus.INITIALIZING ->
                    updated = outcomes.failNeverInitialized(paymentId);
            case VerificationResult.Found(_), VerificationResult.NotFound() ->
                    updated = OptimisticLocking.retryOnce(() -> outcomes.apply(paymentId, result, TransitionCause.system(TransitionTrigger.RECONCILER)));
        }
        if (isUnresolved(updated) && updated.getCreatedAt().isBefore(overdueBefore)) {
            outcomes.expire(paymentId);
            return Outcome.EXPIRED;
        }
        return updated.getStatus() == before ? Outcome.UNCHANGED : Outcome.RESOLVED;
    }

    private static boolean isUnresolved(Payment payment) {
        return payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.UNKNOWN;
    }

    private enum Outcome {
        RESOLVED, EXPIRED, UNCHANGED, UNAVAILABLE
    }
}
