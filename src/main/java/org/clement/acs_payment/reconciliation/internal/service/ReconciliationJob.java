package org.clement.acs_payment.reconciliation.internal.service;

import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.PaymentReconciliation;
import org.clement.acs_payment.reconciliation.internal.config.ReconciliationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("acs.reconciliation.enabled")
public class ReconciliationJob {

    private final PaymentReconciliation reconciliation;
    private final ReconciliationProperties properties;

    @Scheduled(fixedDelayString = "${acs.reconciliation.interval}", initialDelayString = "${acs.reconciliation.interval}")
    public void run() {
        reconciliation.reconcile(properties.staleThreshold(), properties.expiryCutoff(), properties.batchSize());
    }
}
