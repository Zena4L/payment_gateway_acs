package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class ProviderUnavailableException extends ApiException {

    public ProviderUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable", "The payment provider is unavailable. Try again later with a new reference");
    }
}
