package org.clement.acs_payment.payment.internal.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.clement.acs_payment.payment.internal.service.InitiatePaymentCommand;

public record InitiatePaymentRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String reference) {

    public InitiatePaymentCommand toCommand(String ownerId) {
        return InitiatePaymentCommand.builder()
                .ownerId(ownerId)
                .reference(reference)
                .email(email)
                .amount(amount)
                .currency(currency)
                .build();
    }
}
