package org.clement.acs_payment.payment;

import java.time.Duration;

public interface PaymentReconciliation {

    ReconciliationReport reconcile(Duration staleThreshold, Duration expiryCutoff, int batchSize);
}
