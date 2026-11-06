package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class PaymentNotFoundException extends ApiException {

    public PaymentNotFoundException() {
        super(HttpStatus.NOT_FOUND, "payment-not-found", "Payment not found");
    }
}
