package org.clement.acs_payment.paystack.internal.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.paystack.internal.config.PaystackClientConfiguration;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.clement.acs_payment.paystack.internal.dto.InitializeRequestBody;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class PaystackHttpGateway implements PaystackGateway {

    private static final String INITIALIZE_PATH = "/transaction/initialize";
    private static final String VERIFY_PATH = "/transaction/verify/{reference}";
    private static final String REJECTED_REQUEST = "Payment provider rejected the request";
    private static final String REJECTED_CREDENTIALS = "Payment provider rejected the credentials";
    private static final Set<HttpStatus> NOT_PROCESSED = Set.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE);
    private static final Set<HttpStatus> RETRYABLE_READ = Set.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT);

    private final WebClient webClient;
    private final PaystackResponseReader responses;
    private final Retry initializeRetry;
    private final Retry verifyRetry;

    @Getter(AccessLevel.PACKAGE)
    private final CircuitBreaker circuitBreaker;

    public PaystackHttpGateway(@Qualifier(PaystackClientConfiguration.WEB_CLIENT) WebClient webClient,
                               PaystackProperties properties,
                               JsonMapper json) {
        this.webClient = webClient;
        this.responses = new PaystackResponseReader(json);
        this.initializeRetry = PaystackResilience.retry("paystack-initialize", properties, InitializeAttempt::notProcessed);
        this.verifyRetry = PaystackResilience.retry("paystack-verify", properties, VerifyAttempt::retryable);
        this.circuitBreaker = PaystackResilience.circuitBreaker();
    }

    @Override
    public InitializationResult initialize(InitializeTransaction request) {
        try {
            var attempt = circuitBreaker.executeSupplier(Retry.decorateSupplier(initializeRetry, () -> attemptInitialize(request)));
            return attempt.notProcessed() ? new InitializationResult.Unavailable() : attempt.result();
        } catch (CallNotPermittedException ex) {
            log.warn("Paystack circuit breaker is open; initialize not sent for reference {}", request.reference());
            return new InitializationResult.Unavailable();
        }
    }

    @Override
    public VerificationResult verify(String reference) {
        try {
            var attempt = circuitBreaker.executeSupplier(Retry.decorateSupplier(verifyRetry, () -> attemptVerify(reference)));
            return attempt.retryable() ? new VerificationResult.Unavailable() : attempt.result();
        } catch (CallNotPermittedException ex) {
            log.warn("Paystack circuit breaker is open; verify not sent for reference {}", reference);
            return new VerificationResult.Unavailable();
        }
    }

    private InitializeAttempt attemptInitialize(InitializeTransaction request) {
        try {
            var response = exchange(webClient.post()
                    .uri(INITIALIZE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(InitializeRequestBody.from(request)));
            return classifyInitialize(request.reference(), response);
        } catch (RuntimeException ex) {
            if (ex instanceof WebClientRequestException && neverSent(ex)) {
                log.warn("Paystack initialize could not connect for reference {}", request.reference());
                return InitializeAttempt.notProcessedYet(null);
            }
            log.warn("Paystack initialize outcome unknown for reference {} ({})", request.reference(), ex.getClass().getSimpleName());
            return InitializeAttempt.unknown();
        }
    }

    private VerifyAttempt attemptVerify(String reference) {
        try {
            return classifyVerify(reference, exchange(webClient.get().uri(VERIFY_PATH, reference)));
        } catch (RuntimeException ex) {
            log.warn("Paystack verify failed for reference {} ({})", reference, ex.getClass().getSimpleName());
            return VerifyAttempt.retry(null);
        }
    }

    private RawResponse exchange(WebClient.RequestHeadersSpec<?> request) {
        return Objects.requireNonNull(request
                .exchangeToMono(raw -> raw.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new RawResponse(raw.statusCode(), body,
                                PaystackResilience.retryAfter(raw.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER)))))
                .block());
    }

    private InitializeAttempt classifyInitialize(String reference, RawResponse response) {
        var status = response.status();
        if (status.is2xxSuccessful()) {
            return responses.authorizationUrl(response.body())
                    .map(url -> InitializeAttempt.of(new InitializationResult.Initialized(url)))
                    .orElseGet(() -> {
                        log.warn("Paystack initialize returned an unusable success body for reference {}", reference);
                        return InitializeAttempt.unknown();
                    });
        }
        if (is(status, NOT_PROCESSED)) {
            log.warn("Paystack initialize not processed for reference {} (HTTP {})", reference, status.value());
            return InitializeAttempt.notProcessedYet(response.retryAfter());
        }
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            logInvalidCredentials();
            return InitializeAttempt.of(new InitializationResult.Rejected(RejectionReason.UNAUTHORIZED, REJECTED_CREDENTIALS));
        }
        if (status.is4xxClientError()) {
            var message = responses.message(response.body());
            var reason = message.toLowerCase(Locale.ROOT).contains("duplicate") ? RejectionReason.DUPLICATE_REFERENCE : RejectionReason.BAD_REQUEST;
            log.warn("Paystack rejected initialize for reference {} (HTTP {}, {})", reference, status.value(), reason);
            return InitializeAttempt.of(new InitializationResult.Rejected(reason, message.isBlank() ? REJECTED_REQUEST : message));
        }
        log.warn("Paystack initialize outcome unknown for reference {} (HTTP {})", reference, status.value());
        return InitializeAttempt.unknown();
    }

    private VerifyAttempt classifyVerify(String reference, RawResponse response) {
        var status = response.status();
        if (status.isSameCodeAs(HttpStatus.OK)) {
            return found(reference, response.body());
        }
        if (is(status, RETRYABLE_READ) || !status.is4xxClientError()) {
            log.warn("Paystack verify temporarily failed for reference {} (HTTP {})", reference, status.value());
            return VerifyAttempt.retry(response.retryAfter());
        }
        var message = responses.message(response.body());
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)
                || (status.isSameCodeAs(HttpStatus.BAD_REQUEST) && message.toLowerCase(Locale.ROOT).contains("not found"))) {
            log.info("Paystack has no transaction for reference {}", reference);
            return VerifyAttempt.of(new VerificationResult.NotFound());
        }
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            logInvalidCredentials();
            return VerifyAttempt.of(new VerificationResult.Rejected(REJECTED_CREDENTIALS));
        }
        log.warn("Paystack rejected verify for reference {} (HTTP {})", reference, status.value());
        return VerifyAttempt.of(new VerificationResult.Rejected(message.isBlank() ? REJECTED_REQUEST : message));
    }

    private VerifyAttempt found(String reference, String body) {
        var transaction = responses.transaction(body);
        if (transaction.isEmpty()) {
            log.warn("Paystack verify returned an unusable success body for reference {}", reference);
            return VerifyAttempt.retry(null);
        }
        if (transaction.get().status() == ProviderStatus.UNRECOGNIZED) {
            log.warn("Paystack returned unrecognised transaction status '{}' for reference {}", transaction.get().rawStatus(), reference);
        }
        return VerifyAttempt.of(new VerificationResult.Found(transaction.get()));
    }

    private static boolean is(HttpStatusCode status, Set<HttpStatus> statuses) {
        return statuses.stream().anyMatch(status::isSameCodeAs);
    }

    private static void logInvalidCredentials() {
        log.error("Paystack rejected our credentials (HTTP 401); check ACS_PAYSTACK_SECRET_KEY");
    }

    private static boolean neverSent(Throwable ex) {
        for (var cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private record RawResponse(HttpStatusCode status, String body, @Nullable Duration retryAfter) {
    }

    private record InitializeAttempt(InitializationResult result, boolean notProcessed, boolean providerFailure,
                                     @Nullable Duration retryAfter) implements PaystackResilience.CallOutcome {

        static InitializeAttempt of(InitializationResult result) {
            return new InitializeAttempt(result, false, false, null);
        }

        static InitializeAttempt notProcessedYet(@Nullable Duration retryAfter) {
            return new InitializeAttempt(new InitializationResult.Unavailable(), true, true, retryAfter);
        }

        static InitializeAttempt unknown() {
            return new InitializeAttempt(new InitializationResult.OutcomeUnknown(), false, true, null);
        }
    }

    private record VerifyAttempt(VerificationResult result, boolean retryable, @Nullable Duration retryAfter)
            implements PaystackResilience.CallOutcome {

        static VerifyAttempt of(VerificationResult result) {
            return new VerifyAttempt(result, false, null);
        }

        static VerifyAttempt retry(@Nullable Duration retryAfter) {
            return new VerifyAttempt(new VerificationResult.Unavailable(), true, retryAfter);
        }

        @Override
        public boolean providerFailure() {
            return retryable;
        }
    }
}
