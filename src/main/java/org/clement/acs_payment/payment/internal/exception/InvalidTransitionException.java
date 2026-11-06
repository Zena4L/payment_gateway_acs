package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidTransitionException extends ApiException {

    public InvalidTransitionException(PaymentStatus from, PaymentStatus to) {
        super(HttpStatus.CONFLICT, "invalid-transition", "Payment cannot move from " + from + " to " + to);
    }
}
