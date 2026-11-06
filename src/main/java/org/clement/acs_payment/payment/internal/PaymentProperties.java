package org.clement.acs_payment.payment.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("acs.payment")
record PaymentProperties(
        @NotEmpty Set<@Pattern(regexp = "[A-Z]{3}") String> supportedCurrencies) {
}
