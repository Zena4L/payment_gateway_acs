package org.clement.acs_payment.payment.internal.dto;

import java.math.BigDecimal;
import lombok.Builder;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.jspecify.annotations.Nullable;

@Builder
public record PaymentResponse(
        String reference,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        @Nullable String authorizationUrl,
        String message) {

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .reference(payment.getReference())
                .status(payment.getStatus())
                .amount(payment.getAmount())
                .currency(payment.getCurrency().getCurrencyCode())
                .authorizationUrl(payment.getAuthorizationUrl())
                .message(messageFor(payment.getStatus()))
                .build();
    }

    private static String messageFor(PaymentStatus status) {
        return switch (status) {
            case INITIALIZING -> "Payment is being initialized. Retry shortly to get the checkout link.";
            case PENDING -> "Payment initialized. Redirect the customer to the authorization URL to complete payment.";
            case UNKNOWN -> "We could not confirm that the payment was created. Its status is being confirmed.";
            case REQUIRES_REVIEW -> "Payment details did not match the provider's records and are under review.";
            case SUCCESS -> "Payment confirmed by the provider.";
            case FAILED -> "Payment could not be completed.";
            case EXPIRED -> "Payment expired before it was completed.";
        };
    }
}
