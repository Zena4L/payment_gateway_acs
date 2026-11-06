package org.clement.acs_payment.payment.internal.exception;

import java.util.Map;
import java.util.UUID;
import org.clement.acs_payment.shared.ApiException;
import org.springframework.http.HttpStatus;

public class DuplicatePaymentException extends ApiException {

    public DuplicatePaymentException(String existingReference, UUID existingTransactionId) {
        super(HttpStatus.CONFLICT, "duplicate-payment",
                "A matching payment was created moments ago; resume it instead of creating another",
                Map.of("existingReference", existingReference, "existingTransactionId", existingTransactionId));
    }
}
