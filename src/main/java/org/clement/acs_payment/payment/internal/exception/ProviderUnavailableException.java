package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class ProviderUnavailableException extends ApiException {

    private static final String INITIATION_MESSAGE = "The payment provider is unavailable. Try again later with a new reference";
    private static final String VERIFICATION_MESSAGE = "The payment provider is unavailable. The payment is unchanged; try again later";

    public ProviderUnavailableException() {
        this(INITIATION_MESSAGE);
    }

    private ProviderUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable", message);
    }

    public static ProviderUnavailableException duringVerification() {
        return new ProviderUnavailableException(VERIFICATION_MESSAGE);
    }
}
