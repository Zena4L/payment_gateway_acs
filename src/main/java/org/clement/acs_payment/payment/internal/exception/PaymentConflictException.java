package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class PaymentConflictException extends ApiException {

    public PaymentConflictException() {
        super(HttpStatus.CONFLICT, "payment-conflict", "This reference is already used for a different payment");
    }
}
