package org.clement.acs_payment.reconciliation.internal.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.clement.acs_payment.payment.PaymentReconciliation;
import org.clement.acs_payment.reconciliation.internal.config.ReconciliationProperties;
import org.junit.jupiter.api.Test;

class ReconciliationJobTest {

    @Test
    void reconcilesWithConfiguredThresholdCutoffAndBatchSize() {
        var reconciliation = mock(PaymentReconciliation.class);
        var properties = new ReconciliationProperties(true, Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ofHours(24), 50);

        new ReconciliationJob(reconciliation, properties).run();

        verify(reconciliation).reconcile(Duration.ofMinutes(20), Duration.ofHours(24), 50);
    }
}
