package org.clement.acs_payment.paystack.internal.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("acs.paystack")
record PaystackProperties(
        @NotNull URI baseUrl,
        @NotBlank String secretKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration responseTimeout) {
}
