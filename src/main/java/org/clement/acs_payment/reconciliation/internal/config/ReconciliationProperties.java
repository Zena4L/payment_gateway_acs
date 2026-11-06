package org.clement.acs_payment.reconciliation.internal.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("acs.reconciliation")
public record ReconciliationProperties(
        boolean enabled,
        @NotNull Duration interval,
        @NotNull Duration staleThreshold,
        @NotNull Duration expiryCutoff,
        @Positive int batchSize) {

    @AssertTrue(message = "expiryCutoff must be longer than staleThreshold")
    public boolean isExpiryCutoffAfterStaleThreshold() {
        return staleThreshold == null || expiryCutoff == null || expiryCutoff.compareTo(staleThreshold) > 0;
    }
}
