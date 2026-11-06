package org.clement.acs_payment.payment.internal.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    INITIALIZING, PENDING, UNKNOWN, REQUIRES_REVIEW, SUCCESS, FAILED, EXPIRED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            INITIALIZING, EnumSet.of(PENDING, UNKNOWN, FAILED),
            PENDING, EnumSet.of(SUCCESS, FAILED, EXPIRED, REQUIRES_REVIEW),
            UNKNOWN, EnumSet.of(PENDING, SUCCESS, FAILED, EXPIRED, REQUIRES_REVIEW),
            REQUIRES_REVIEW, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            EXPIRED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canMoveTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isFinal() {
        return ALLOWED.get(this).isEmpty();
    }
}
