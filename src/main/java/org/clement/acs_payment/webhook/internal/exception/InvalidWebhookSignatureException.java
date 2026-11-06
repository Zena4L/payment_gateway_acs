package org.clement.acs_payment.webhook.internal.exception;

import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidWebhookSignatureException extends ApiException {

    public InvalidWebhookSignatureException() {
        super(HttpStatus.UNAUTHORIZED, "invalid-signature", "Webhook signature is missing or invalid");
    }
}
