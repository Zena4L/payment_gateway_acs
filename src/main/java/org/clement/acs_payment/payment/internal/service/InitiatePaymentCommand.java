package org.clement.acs_payment.payment.internal.service;

import java.math.BigDecimal;
import lombok.Builder;
import org.clement.acs_payment.payment.internal.model.Actor;

@Builder
public record InitiatePaymentCommand(Actor initiator, String reference, String email, BigDecimal amount, String currency) {

    public String ownerId() {
        return initiator.ownerId();
    }
}
