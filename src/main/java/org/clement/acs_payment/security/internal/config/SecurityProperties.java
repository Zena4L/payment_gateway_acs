package org.clement.acs_payment.security.internal.config;

import jakarta.validation.constraints.Pattern;
import org.clement.acs_payment.security.internal.model.ApiKey;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("acs.security")
public record SecurityProperties(
        @Pattern(regexp = "(" + ApiKey.FORMAT + ")?",
                message = "must be an API key in the acs_<keyId>.<secret> format") @Nullable String bootstrapAdminKey) {
}
