package org.clement.acs_payment.security.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class ApiKeyNotFoundException extends ApiException {

    public ApiKeyNotFoundException() {
        super(HttpStatus.NOT_FOUND, "api-key-not-found", "API key not found");
    }
}
