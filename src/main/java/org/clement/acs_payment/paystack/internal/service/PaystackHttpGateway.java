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
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.paystack.internal.config.PaystackClientConfiguration;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.clement.acs_payment.paystack.internal.dto.InitializeRequestBody;
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
    private static final int MAX_STORED_MESSAGE = 255;

    private final WebClient webClient;
    private final JsonMapper json;
    private final Retry retry;

    @Getter(AccessLevel.PACKAGE)
    private final CircuitBreaker circuitBreaker;

    public PaystackHttpGateway(@Qualifier(PaystackClientConfiguration.WEB_CLIENT) WebClient webClient,
                               PaystackProperties properties,
                               JsonMapper json) {
        this.webClient = webClient;
        this.json = json;
        this.retry = Retry.of("paystack-initialize", RetryConfig.<Attempt>custom()
                .maxAttempts(properties.retry().maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.retry().initialBackoff(), 2.0, 0.5))
                .retryOnResult(Attempt::notProcessed)
                .retryOnException(ex -> false)
                .build());
        this.circuitBreaker = CircuitBreaker.of("paystack", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordResult(result -> result instanceof Attempt attempt && attempt.providerFailure())
                .build());
    }

    @Override
    public InitializationResult initialize(InitializeTransaction request) {
        try {
            var attempt = circuitBreaker.executeSupplier(Retry.decorateSupplier(retry, () -> attemptInitialize(request)));
            return attempt.notProcessed() ? new InitializationResult.Unavailable() : attempt.result();
        } catch (CallNotPermittedException ex) {
            log.warn("Paystack circuit breaker is open; initialize not sent for reference {}", request.reference());
            return new InitializationResult.Unavailable();
        }
    }

    private Attempt attemptInitialize(InitializeTransaction request) {
        try {
            var response = Objects.requireNonNull(webClient.post()
                    .uri(INITIALIZE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(InitializeRequestBody.from(request))
                    .exchangeToMono(raw -> raw.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new RawResponse(raw.statusCode().value(), body)))
                    .block());
            return classify(request.reference(), response);
        } catch (WebClientRequestException ex) {
            if (neverSent(ex)) {
                log.warn("Paystack initialize could not connect for reference {}", request.reference());
                return Attempt.notProcessedYet();
            }
            log.warn("Paystack initialize outcome unknown for reference {} ({})", request.reference(), ex.getClass().getSimpleName());
            return Attempt.unknown();
        } catch (RuntimeException ex) {
            log.warn("Paystack initialize outcome unknown for reference {} ({})", request.reference(), ex.getClass().getSimpleName());
            return Attempt.unknown();
        }
    }

    private Attempt classify(String reference, RawResponse response) {
        var status = response.status();
        if (status == 200 || status == 201) {
            return initialized(reference, response.body());
        }
        if (status == 429 || status == 503) {
            log.warn("Paystack initialize not processed for reference {} (HTTP {})", reference, status);
            return Attempt.notProcessedYet();
        }
        if (status == 401) {
            log.error("Paystack rejected our credentials (HTTP 401); check ACS_PAYSTACK_SECRET_KEY");
            return Attempt.of(new InitializationResult.Rejected(RejectionReason.UNAUTHORIZED, "Payment provider rejected the credentials"));
        }
        if (status >= 400 && status < 500) {
            var message = providerMessage(response.body());
            var reason = message.toLowerCase(Locale.ROOT).contains("duplicate") ? RejectionReason.DUPLICATE_REFERENCE : RejectionReason.BAD_REQUEST;
            log.warn("Paystack rejected initialize for reference {} (HTTP {}, {})", reference, status, reason);
            return Attempt.of(new InitializationResult.Rejected(reason, message.isBlank() ? "Payment provider rejected the request" : message));
        }
        log.warn("Paystack initialize outcome unknown for reference {} (HTTP {})", reference, status);
        return Attempt.unknown();
    }

    private Attempt initialized(String reference, String body) {
        var root = parse(body);
        var data = root.path("data");
        var url = data.path("authorization_url").asString("");
        var accessCode = data.path("access_code").asString("");
        if (!root.path("status").asBoolean(false) || url.isBlank() || accessCode.isBlank()) {
            log.warn("Paystack initialize returned an unusable success body for reference {}", reference);
            return Attempt.unknown();
        }
        return Attempt.of(new InitializationResult.Initialized(url, accessCode));
    }

    private String providerMessage(String body) {
        var message = parse(body).path("message").asString("").strip();
        return message.length() > MAX_STORED_MESSAGE ? message.substring(0, MAX_STORED_MESSAGE) : message;
    }

    private JsonNode parse(String body) {
        try {
            return body.isBlank() ? json.missingNode() : json.readTree(body);
        } catch (JacksonException ex) {
            return json.missingNode();
        }
    }

    private static boolean neverSent(Throwable ex) {
        for (var cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private record RawResponse(int status, String body) {
    }

    private record Attempt(InitializationResult result, boolean notProcessed, boolean providerFailure) {

        static Attempt of(InitializationResult result) {
            return new Attempt(result, false, false);
        }

        static Attempt notProcessedYet() {
            return new Attempt(new InitializationResult.Unavailable(), true, true);
        }

        static Attempt unknown() {
            return new Attempt(new InitializationResult.OutcomeUnknown(), false, true);
        }
    }
}
