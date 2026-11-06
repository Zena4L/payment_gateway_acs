package org.clement.acs_payment.reconciliation.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("acs.reconciliation")
record ReconciliationProperties(
        @NotNull Duration interval,
        @NotNull Duration staleThreshold,
        @NotNull Duration expiryCutoff,
        @Positive int batchSize) {
}
