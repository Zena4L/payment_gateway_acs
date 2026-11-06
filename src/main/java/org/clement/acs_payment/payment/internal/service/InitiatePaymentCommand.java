package org.clement.acs_payment.payment.internal.service;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record InitiatePaymentCommand(String ownerId, String reference, String email, BigDecimal amount, String currency) {
}
