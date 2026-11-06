package org.clement.acs_payment.paystack.internal.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.jspecify.annotations.Nullable;

@UtilityClass
class PaystackResilience {

    interface CallOutcome {

        boolean providerFailure();

        @Nullable Duration retryAfter();
    }

    static CircuitBreaker circuitBreaker() {
        return CircuitBreaker.of("paystack", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordResult(result -> result instanceof CallOutcome outcome && outcome.providerFailure())
                .build());
    }

    static <T extends CallOutcome> Retry retry(String name, PaystackProperties properties, Predicate<T> retryOnResult) {
        var backoff = IntervalFunction.ofExponentialRandomBackoff(properties.retry().initialBackoff(), 2.0, 0.5);
        var maxRetryAfter = properties.retry().maxRetryAfter();
        IntervalBiFunction<T> interval = (attempt, outcome) -> waitMillis(attempt, outcome, backoff, maxRetryAfter);
        return Retry.of(name, RetryConfig.<T>custom()
                .maxAttempts(properties.retry().maxAttempts())
                .intervalBiFunction(interval)
                .retryOnResult(retryOnResult)
                .retryOnException(ex -> false)
                .build());
    }

    static @Nullable Duration retryAfter(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            var seconds = Long.parseLong(header.strip());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static <T extends CallOutcome> long waitMillis(int attempt, Either<Throwable, T> outcome,
                                                          IntervalFunction backoff, Duration maxRetryAfter) {
        if (outcome.isRight()) {
            var retryAfter = outcome.get().retryAfter();
            if (retryAfter != null) {
                return Math.min(retryAfter.toMillis(), maxRetryAfter.toMillis());
            }
        }
        return backoff.apply(attempt);
    }
}
