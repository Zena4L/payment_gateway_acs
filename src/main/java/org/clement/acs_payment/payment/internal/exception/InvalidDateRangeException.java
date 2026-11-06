package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidDateRangeException extends ApiException {

    public InvalidDateRangeException() {
        super(HttpStatus.BAD_REQUEST, "invalid-date-range", "createdFrom must be before createdTo");
    }
}
