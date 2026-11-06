package org.clement.acs_payment.shared;

import java.util.Optional;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

@UtilityClass
public class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
