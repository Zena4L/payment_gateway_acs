package org.clement.acs_payment.paystack.internal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("acs.paystack")
public record PaystackProperties(
        @NotNull URI baseUrl,
        @NotBlank String secretKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration responseTimeout,
        @NotNull @Valid Retry retry) {

    public record Retry(@Positive int maxAttempts, @NotNull Duration initialBackoff, @NotNull Duration maxRetryAfter) {
    }

    @Override
    public String toString() {
        return "PaystackProperties[baseUrl=" + baseUrl + "]";
    }
}
