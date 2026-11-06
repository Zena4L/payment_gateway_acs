package org.clement.acs_payment.payment.internal.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;

@Getter
@RequiredArgsConstructor
public enum ResolutionOutcome {
    SUCCESS(PaymentStatus.SUCCESS),
    FAILED(PaymentStatus.FAILED);

    private final PaymentStatus status;
}
