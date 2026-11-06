package org.clement.acs_payment.payment;

public record ReconciliationReport(int claimed, int resolved, int expired, int unavailable) {
}
