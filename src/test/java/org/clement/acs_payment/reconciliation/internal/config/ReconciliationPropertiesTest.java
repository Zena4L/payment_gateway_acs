package org.clement.acs_payment.reconciliation.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconciliationPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsCutoffLongerThanThreshold() {
        var properties = new ReconciliationProperties(true, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24), 100);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsCutoffNotLongerThanThreshold() {
        var properties = new ReconciliationProperties(true, Duration.ofMinutes(15), Duration.ofHours(2), Duration.ofHours(1), 100);

        assertThat(validator.validate(properties))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("expiryCutoff must be longer than staleThreshold");
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        var properties = new ReconciliationProperties(true, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24), 0);

        assertThat(validator.validate(properties)).isNotEmpty();
    }
}
