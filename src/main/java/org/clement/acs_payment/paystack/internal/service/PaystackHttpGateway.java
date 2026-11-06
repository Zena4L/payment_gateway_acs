package org.clement.acs_payment.paystack.internal.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.paystack.internal.config.PaystackClientConfiguration;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.clement.acs_payment.paystack.internal.dto.InitializeRequestBody;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class PaystackHttpGateway implements PaystackGateway {

    private static final String INITIALIZE_PATH = "/transaction/initialize";
    private static final String VERIFY_PATH = "/transaction/verify/{reference}";
    private static final int MAX_STORED_MESSAGE = 255;
    private static final Set<Integer> RETRYABLE_READ_STATUSES = Set.of(429, 500, 502, 503, 504);
    private static final Set<String> PENDING_STATUSES = Set.of("abandoned", "ongoing", "pending", "processing", "queued");

    private final WebClient webClient;
    private final JsonMapper json;
    private final Retry initializeRetry;
    private final Retry verifyRetry;

    @Getter(AccessLevel.PACKAGE)
    private final CircuitBreaker circuitBreaker;

    public PaystackHttpGateway(@Qualifier(PaystackClientConfiguration.WEB_CLIENT) WebClient webClient,
                               PaystackProperties properties,
                               JsonMapper json) {
        this.webClient = webClient;
        this.json = json;
        this.initializeRetry = retry("paystack-initialize", properties, InitializeAttempt::notProcessed);
        this.verifyRetry = retry("paystack-verify", properties, VerifyAttempt::retryable);
        this.circuitBreaker = CircuitBreaker.of("paystack", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordResult(result -> result instanceof CallOutcome outcome && outcome.providerFailure())
                .build());
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
        } catch (WebClientRequestException ex) {
            if (neverSent(ex)) {
                log.warn("Paystack initialize could not connect for reference {}", request.reference());
                return InitializeAttempt.notProcessedYet();
            }
            log.warn("Paystack initialize outcome unknown for reference {} ({})", request.reference(), ex.getClass().getSimpleName());
            return InitializeAttempt.unknown();
        } catch (RuntimeException ex) {
            log.warn("Paystack initialize outcome unknown for reference {} ({})", request.reference(), ex.getClass().getSimpleName());
            return InitializeAttempt.unknown();
        }
    }

    private VerifyAttempt attemptVerify(String reference) {
        try {
            return classifyVerify(reference, exchange(webClient.get().uri(VERIFY_PATH, reference)));
        } catch (RuntimeException ex) {
            log.warn("Paystack verify failed for reference {} ({})", reference, ex.getClass().getSimpleName());
            return VerifyAttempt.retry();
        }
    }

    private RawResponse exchange(WebClient.RequestHeadersSpec<?> request) {
        return Objects.requireNonNull(request
                .exchangeToMono(raw -> raw.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new RawResponse(raw.statusCode().value(), body)))
                .block());
    }

    private InitializeAttempt classifyInitialize(String reference, RawResponse response) {
        var status = response.status();
        if (status == 200 || status == 201) {
            return initialized(reference, response.body());
        }
        if (status == 429 || status == 503) {
            log.warn("Paystack initialize not processed for reference {} (HTTP {})", reference, status);
            return InitializeAttempt.notProcessedYet();
        }
        if (status == 401) {
            logInvalidCredentials();
            return InitializeAttempt.of(new InitializationResult.Rejected(RejectionReason.UNAUTHORIZED, "Payment provider rejected the credentials"));
        }
        if (status >= 400 && status < 500) {
            var message = providerMessage(response.body());
            var reason = message.toLowerCase(Locale.ROOT).contains("duplicate") ? RejectionReason.DUPLICATE_REFERENCE : RejectionReason.BAD_REQUEST;
            log.warn("Paystack rejected initialize for reference {} (HTTP {}, {})", reference, status, reason);
            return InitializeAttempt.of(new InitializationResult.Rejected(reason, message.isBlank() ? "Payment provider rejected the request" : message));
        }
        log.warn("Paystack initialize outcome unknown for reference {} (HTTP {})", reference, status);
        return InitializeAttempt.unknown();
    }

    private VerifyAttempt classifyVerify(String reference, RawResponse response) {
        var status = response.status();
        if (status == 200) {
            return found(reference, response.body());
        }
        if (RETRYABLE_READ_STATUSES.contains(status)) {
            log.warn("Paystack verify temporarily failed for reference {} (HTTP {})", reference, status);
            return VerifyAttempt.retry();
        }
        var message = providerMessage(response.body());
        if (status == 404 || (status == 400 && message.toLowerCase(Locale.ROOT).contains("not found"))) {
            log.info("Paystack has no transaction for reference {}", reference);
            return VerifyAttempt.of(new VerificationResult.NotFound());
        }
        if (status == 401) {
            logInvalidCredentials();
            return VerifyAttempt.of(new VerificationResult.Rejected("Payment provider rejected the credentials"));
        }
        if (status >= 400 && status < 500) {
            log.warn("Paystack rejected verify for reference {} (HTTP {})", reference, status);
            return VerifyAttempt.of(new VerificationResult.Rejected(message.isBlank() ? "Payment provider rejected the request" : message));
        }
        log.warn("Paystack verify temporarily failed for reference {} (HTTP {})", reference, status);
        return VerifyAttempt.retry();
    }

    private InitializeAttempt initialized(String reference, String body) {
        var root = parse(body);
        var data = root.path("data");
        var url = data.path("authorization_url").asString("");
        var accessCode = data.path("access_code").asString("");
        if (!root.path("status").asBoolean(false) || url.isBlank() || accessCode.isBlank()) {
            log.warn("Paystack initialize returned an unusable success body for reference {}", reference);
            return InitializeAttempt.unknown();
        }
        return InitializeAttempt.of(new InitializationResult.Initialized(url, accessCode));
    }

    private VerifyAttempt found(String reference, String body) {
        var root = parse(body);
        var data = root.path("data");
        var rawStatus = data.path("status").asString("");
        var returnedReference = data.path("reference").asString("");
        var currency = data.path("currency").asString("");
        if (!root.path("status").asBoolean(false) || !data.path("id").isNumber() || !data.path("amount").isNumber()
                || rawStatus.isBlank() || returnedReference.isBlank() || currency.isBlank()) {
            log.warn("Paystack verify returned an unusable success body for reference {}", reference);
            return VerifyAttempt.retry();
        }
        var status = providerStatus(rawStatus);
        if (status == ProviderStatus.UNRECOGNIZED) {
            log.warn("Paystack returned unrecognised transaction status '{}' for reference {}", rawStatus, reference);
        }
        return VerifyAttempt.of(new VerificationResult.Found(ProviderTransaction.builder()
                .id(data.path("id").asLong())
                .status(status)
                .rawStatus(rawStatus)
                .reference(returnedReference)
                .amountMinor(data.path("amount").asLong())
                .currency(currency)
                .channel(optionalText(data.path("channel")))
                .paidAt(instant(data.path("paid_at")))
                .gatewayResponse(truncate(optionalText(data.path("gateway_response"))))
                .build()));
    }

    private static ProviderStatus providerStatus(String rawStatus) {
        var normalised = rawStatus.toLowerCase(Locale.ROOT);
        if (normalised.equals("success")) {
            return ProviderStatus.SUCCESS;
        }
        if (normalised.equals("failed")) {
            return ProviderStatus.FAILED;
        }
        if (normalised.equals("reversed")) {
            return ProviderStatus.REVERSED;
        }
        return PENDING_STATUSES.contains(normalised) ? ProviderStatus.PENDING : ProviderStatus.UNRECOGNIZED;
    }

    private String providerMessage(String body) {
        var message = parse(body).path("message").asString("").strip();
        return Objects.requireNonNull(truncate(message));
    }

    private JsonNode parse(String body) {
        try {
            return body.isBlank() ? json.missingNode() : json.readTree(body);
        } catch (JacksonException ex) {
            return json.missingNode();
        }
    }

    private static @Nullable String optionalText(JsonNode node) {
        var text = node.asString("");
        return text.isBlank() ? null : text;
    }

    private static @Nullable Instant instant(JsonNode node) {
        var text = optionalText(node);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static @Nullable String truncate(@Nullable String text) {
        return text != null && text.length() > MAX_STORED_MESSAGE ? text.substring(0, MAX_STORED_MESSAGE) : text;
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

    private static <T> Retry retry(String name, PaystackProperties properties, Predicate<T> retryOnResult) {
        return Retry.of(name, RetryConfig.<T>custom()
                .maxAttempts(properties.retry().maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.retry().initialBackoff(), 2.0, 0.5))
                .retryOnResult(retryOnResult)
                .retryOnException(ex -> false)
                .build());
    }

    private interface CallOutcome {

        boolean providerFailure();
    }

    private record RawResponse(int status, String body) {
    }

    private record InitializeAttempt(InitializationResult result, boolean notProcessed, boolean providerFailure) implements CallOutcome {

        static InitializeAttempt of(InitializationResult result) {
            return new InitializeAttempt(result, false, false);
        }

        static InitializeAttempt notProcessedYet() {
            return new InitializeAttempt(new InitializationResult.Unavailable(), true, true);
        }

        static InitializeAttempt unknown() {
            return new InitializeAttempt(new InitializationResult.OutcomeUnknown(), false, true);
        }
    }

    private record VerifyAttempt(VerificationResult result, boolean retryable) implements CallOutcome {

        static VerifyAttempt of(VerificationResult result) {
            return new VerifyAttempt(result, false);
        }

        static VerifyAttempt retry() {
            return new VerifyAttempt(new VerificationResult.Unavailable(), true);
        }

        @Override
        public boolean providerFailure() {
            return retryable;
        }
    }
}
