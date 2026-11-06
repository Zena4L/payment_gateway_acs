package org.clement.acs_payment.payment.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.jspecify.annotations.Nullable;

@Builder
public record TransactionResponse(
        UUID id,
        String reference,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String email,
        @Nullable String channel,
        @Nullable Long providerTransactionId,
        @Nullable Instant paidAt,
        @Nullable String authorizationUrl,
        Instant createdAt,
        Instant updatedAt) {

    public static TransactionResponse from(Payment payment) {
        return TransactionResponse.builder()
                .id(payment.getId())
                .reference(payment.getReference())
                .status(payment.getStatus())
                .amount(payment.getAmount())
                .currency(payment.getCurrency().getCurrencyCode())
                .email(payment.getEmail())
                .channel(payment.getChannel())
                .providerTransactionId(payment.getProviderTransactionId())
                .paidAt(payment.getPaidAt())
                .authorizationUrl(payment.getAuthorizationUrl())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }
}
