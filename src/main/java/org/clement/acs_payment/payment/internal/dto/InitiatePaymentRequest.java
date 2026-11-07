package org.clement.acs_payment.payment.internal.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.clement.acs_payment.payment.internal.model.Actor;
import org.clement.acs_payment.payment.internal.service.InitiatePaymentCommand;

public record InitiatePaymentRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank(message = "reference is required")
        @Size(max = 64, message = "reference must be at most 64 characters long")
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = REFERENCE_CHARACTERS) String reference) {

    public static final String REFERENCE_CHARACTERS = "reference may only contain letters (A-Z, a-z), digits (0-9), "
            + "dots (.), underscores (_) and hyphens (-); spaces and other characters are not allowed, for example use "
            + "'test-demo' instead of 'test demo'";

    public InitiatePaymentCommand toCommand(Actor initiator) {
        return InitiatePaymentCommand.builder()
                .initiator(initiator)
                .reference(reference)
                .email(email)
                .amount(amount)
                .currency(currency)
                .build();
    }
}
