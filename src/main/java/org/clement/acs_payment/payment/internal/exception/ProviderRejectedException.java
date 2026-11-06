package org.clement.acs_payment.payment.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class ProviderRejectedException extends ApiException {

    public ProviderRejectedException() {
        super(HttpStatus.BAD_GATEWAY, "provider-rejected", "The payment provider rejected the request");
    }
}
