package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class UnsupportedCurrencyException extends ApiException {

    public UnsupportedCurrencyException(String currency) {
        super(HttpStatus.BAD_REQUEST, "unsupported-currency", "Currency " + currency + " is not supported");
    }
}
