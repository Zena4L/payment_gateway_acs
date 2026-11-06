package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidMoneyException extends ApiException {

    public InvalidMoneyException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid-amount", message);
    }
}
