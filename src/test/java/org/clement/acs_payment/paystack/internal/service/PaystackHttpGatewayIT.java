package org.clement.acs_payment.paystack.internal.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.paystack.internal.config.PaystackClientConfiguration;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

class PaystackHttpGatewayIT {

    private static final String INITIALIZE = "/transaction/initialize";
    private static final String SECRET = "sk_test_gateway";
    private static final InitializeTransaction REQUEST = InitializeTransaction.builder()
            .reference("PAY-1")
            .email("jane@example.com")
            .amountMinor(15000)
            .currency("GHS")
            .build();
    private static final String SUCCESS_BODY = """
            {"status":true,"message":"Authorization URL created",
             "data":{"authorization_url":"https://checkout.paystack.com/abc","access_code":"abc","reference":"PAY-1"}}
            """;

    @RegisterExtension
    static WireMockExtension paystack = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void sendsAmountInPesewasWithBearerKeyAndReturnsCheckoutLink() {
        stubInitialize(200, SUCCESS_BODY);

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isEqualTo(new InitializationResult.Initialized("https://checkout.paystack.com/abc"));
        paystack.verify(1, postRequestedFor(urlEqualTo(INITIALIZE))
                .withHeader("Authorization", equalTo("Bearer " + SECRET))
                .withRequestBody(equalToJson("""
                        {"email":"jane@example.com","amount":15000,"currency":"GHS","reference":"PAY-1"}
                        """)));
    }

    @Test
    void treatsSuccessWithoutCheckoutLinkAsUnknown() {
        stubInitialize(200, "{\"status\":true,\"data\":{}}");

        assertThat(gateway(paystack.baseUrl()).initialize(REQUEST)).isInstanceOf(InitializationResult.OutcomeUnknown.class);
    }

    @Test
    void treatsMalformedSuccessBodyAsUnknown() {
        stubInitialize(200, "<html>not json</html>");

        assertThat(gateway(paystack.baseUrl()).initialize(REQUEST)).isInstanceOf(InitializationResult.OutcomeUnknown.class);
    }

    @Test
    void treatsEmptySuccessBodyAsUnknown() {
        stubInitialize(200, "");

        assertThat(gateway(paystack.baseUrl()).initialize(REQUEST)).isInstanceOf(InitializationResult.OutcomeUnknown.class);
    }

    @Test
    void recognisesDuplicateReference() {
        stubInitialize(400, "{\"status\":false,\"message\":\"Duplicate Transaction Reference\"}");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Rejected.class)
                .extracting("reason").isEqualTo(RejectionReason.DUPLICATE_REFERENCE);
        paystack.verify(1, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void rejectsBadRequestWithoutRetrying() {
        stubInitialize(400, "{\"status\":false,\"message\":\"Currency not supported by merchant\"}");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isEqualTo(new InitializationResult.Rejected(RejectionReason.BAD_REQUEST, "Currency not supported by merchant"));
        paystack.verify(1, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void rejectsBadRequestWithUnparseableBody() {
        stubInitialize(400, "oops");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isEqualTo(new InitializationResult.Rejected(RejectionReason.BAD_REQUEST, "Payment provider rejected the request"));
    }

    @Test
    void rejectsInvalidCredentialsWithoutRetrying() {
        stubInitialize(401, "{\"status\":false,\"message\":\"Invalid key\"}");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Rejected.class)
                .extracting("reason").isEqualTo(RejectionReason.UNAUTHORIZED);
        paystack.verify(1, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void retriesRateLimitThenSucceeds() {
        paystack.stubFor(post(INITIALIZE).inScenario("rate-limit").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("recovered"));
        paystack.stubFor(post(INITIALIZE).inScenario("rate-limit").whenScenarioStateIs("recovered")
                .willReturn(json(200, SUCCESS_BODY)));

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Initialized.class);
        paystack.verify(2, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void waitsForRetryAfterBeforeRetryingInitialize() {
        paystack.stubFor(post(INITIALIZE).inScenario("retry-after").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("recovered"));
        paystack.stubFor(post(INITIALIZE).inScenario("retry-after").whenScenarioStateIs("recovered")
                .willReturn(json(200, SUCCESS_BODY)));

        var started = System.nanoTime();
        var result = gateway(paystack.baseUrl()).initialize(REQUEST);
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isInstanceOf(InitializationResult.Initialized.class);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(950));
        paystack.verify(2, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void capsRetryAfterAtConfiguredMaximum() {
        paystack.stubFor(post(INITIALIZE).inScenario("long-retry-after").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")).willSetStateTo("recovered"));
        paystack.stubFor(post(INITIALIZE).inScenario("long-retry-after").whenScenarioStateIs("recovered")
                .willReturn(json(200, SUCCESS_BODY)));

        var started = System.nanoTime();
        gateway(paystack.baseUrl()).initialize(REQUEST);
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isBetween(Duration.ofMillis(1950), Duration.ofSeconds(10));
    }

    @Test
    void waitsForRetryAfterBeforeRetryingVerify() {
        paystack.stubFor(get("/transaction/verify/PAY-1").inScenario("verify-retry-after").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("recovered"));
        paystack.stubFor(get("/transaction/verify/PAY-1").inScenario("verify-retry-after").whenScenarioStateIs("recovered")
                .willReturn(json(200, verifyBody("success", "PAY-1", 15000, "GHS"))));

        var started = System.nanoTime();
        var result = gateway(paystack.baseUrl()).verify("PAY-1");

        assertThat(result).isInstanceOf(VerificationResult.Found.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(950));
    }

    @Test
    void givesUpAfterThreeRateLimitedAttempts() {
        stubInitialize(429, "");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Unavailable.class);
        paystack.verify(3, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void retriesServiceUnavailable() {
        stubInitialize(503, "");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Unavailable.class);
        paystack.verify(3, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void neverRetriesInternalServerError() {
        stubInitialize(500, "{\"status\":false}");

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.OutcomeUnknown.class);
        paystack.verify(1, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void neverRetriesTimeout() {
        paystack.stubFor(post(INITIALIZE).willReturn(json(200, SUCCESS_BODY).withFixedDelay(1500)));

        var result = gateway(paystack.baseUrl()).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.OutcomeUnknown.class);
        paystack.verify(1, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void retriesWhenConnectionIsRefusedThenReportsUnavailable() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        var result = gateway("http://localhost:" + closedPort).initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Unavailable.class);
    }

    @Test
    void failsFastWhenCircuitBreakerIsOpen() {
        stubInitialize(200, SUCCESS_BODY);
        var gateway = gateway(paystack.baseUrl());
        gateway.getCircuitBreaker().transitionToOpenState();

        var result = gateway.initialize(REQUEST);

        assertThat(result).isInstanceOf(InitializationResult.Unavailable.class);
        paystack.verify(0, postRequestedFor(urlEqualTo(INITIALIZE)));
    }

    @Test
    void verifiesTransactionAndReadsProviderDetails() {
        stubVerify("PAY-1", 200, verifyBody("success", "PAY-1", 15000, "GHS"));

        var result = gateway(paystack.baseUrl()).verify("PAY-1");

        assertThat(result).isEqualTo(new VerificationResult.Found(ProviderTransaction.builder()
                .id(4099260516L)
                .status(ProviderStatus.SUCCESS)
                .rawStatus("success")
                .reference("PAY-1")
                .amountMinor(15000)
                .currency("GHS")
                .channel("card")
                .paidAt(Instant.parse("2026-11-06T10:15:30Z"))
                .gatewayResponse("Approved")
                .build()));
        paystack.verify(1, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET)));
    }

    @ParameterizedTest
    @CsvSource({
            "success, SUCCESS",
            "failed, FAILED",
            "abandoned, PENDING",
            "ongoing, PENDING",
            "pending, PENDING",
            "processing, PENDING",
            "queued, PENDING",
            "reversed, REVERSED",
            "something-new, UNRECOGNIZED"
    })
    void mapsPaystackStatusToProviderStatus(String paystackStatus, ProviderStatus expected) {
        stubVerify("PAY-1", 200, verifyBody(paystackStatus, "PAY-1", 15000, "GHS"));

        var result = gateway(paystack.baseUrl()).verify("PAY-1");

        assertThat(((VerificationResult.Found) result).transaction().status()).isEqualTo(expected);
    }

    @Test
    void reportsUnknownReferenceAsNotFound() {
        stubVerify("PAY-1", 400, "{\"status\":false,\"message\":\"Transaction reference not found\"}");

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.NotFound.class);
        paystack.verify(1, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @Test
    void reports404AsNotFound() {
        stubVerify("PAY-1", 404, "");

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.NotFound.class);
    }

    @Test
    void rejectsVerifyWithInvalidCredentialsWithoutRetrying() {
        stubVerify("PAY-1", 401, "{\"status\":false,\"message\":\"Invalid key\"}");

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.Rejected.class);
        paystack.verify(1, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @Test
    void retriesVerifyAfterRateLimit() {
        paystack.stubFor(get("/transaction/verify/PAY-1").inScenario("verify-rate-limit").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("recovered"));
        paystack.stubFor(get("/transaction/verify/PAY-1").inScenario("verify-rate-limit").whenScenarioStateIs("recovered")
                .willReturn(json(200, verifyBody("success", "PAY-1", 15000, "GHS"))));

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.Found.class);
        paystack.verify(2, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void retriesVerifyOnServerErrorsThenReportsUnavailable(int status) {
        stubVerify("PAY-1", status, "");

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.Unavailable.class);
        paystack.verify(3, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @Test
    void retriesVerifyAfterTimeout() {
        paystack.stubFor(get("/transaction/verify/PAY-1").willReturn(json(200, verifyBody("success", "PAY-1", 15000, "GHS")).withFixedDelay(1500)));

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.Unavailable.class);
        paystack.verify(3, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @Test
    void retriesVerifyOnMalformedBody() {
        stubVerify("PAY-1", 200, "{\"status\":true,\"data\":{\"status\":\"success\"}}");

        assertThat(gateway(paystack.baseUrl()).verify("PAY-1")).isInstanceOf(VerificationResult.Unavailable.class);
        paystack.verify(3, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @Test
    void verifyFailsFastWhenCircuitBreakerIsOpen() {
        stubVerify("PAY-1", 200, verifyBody("success", "PAY-1", 15000, "GHS"));
        var gateway = gateway(paystack.baseUrl());
        gateway.getCircuitBreaker().transitionToOpenState();

        assertThat(gateway.verify("PAY-1")).isInstanceOf(VerificationResult.Unavailable.class);
        paystack.verify(0, getRequestedFor(urlEqualTo("/transaction/verify/PAY-1")));
    }

    @Test
    void encodesReferenceInVerifyPath() {
        paystack.stubFor(get(urlEqualTo("/transaction/verify/PAY%201")).willReturn(json(200, verifyBody("pending", "PAY 1", 15000, "GHS"))));

        var result = gateway(paystack.baseUrl()).verify("PAY 1");

        assertThat(result).isInstanceOf(VerificationResult.Found.class);
    }

    @Test
    void leavesOptionalDetailsEmptyWhenMissing() {
        stubVerify("PAY-1", 200, """
                {"status":true,"data":{"id":1,"status":"abandoned","reference":"PAY-1","amount":15000,"currency":"GHS","paid_at":null}}
                """);

        var transaction = ((VerificationResult.Found) gateway(paystack.baseUrl()).verify("PAY-1")).transaction();

        assertThat(transaction.channel()).isNull();
        assertThat(transaction.paidAt()).isNull();
        assertThat(transaction.gatewayResponse()).isNull();
    }

    private static String verifyBody(String status, String reference, long amount, String currency) {
        return """
                {"status":true,"message":"Verification successful",
                 "data":{"id":4099260516,"status":"%s","reference":"%s","amount":%d,"currency":"%s",
                         "channel":"card","paid_at":"2026-11-06T10:15:30.000Z","gateway_response":"Approved"}}
                """.formatted(status, reference, amount, currency);
    }

    private static void stubVerify(String reference, int status, String body) {
        paystack.stubFor(get("/transaction/verify/" + reference).willReturn(json(status, body)));
    }

    private static PaystackHttpGateway gateway(String baseUrl) {
        var properties = new PaystackProperties(
                URI.create(baseUrl),
                SECRET,
                Duration.ofMillis(500),
                Duration.ofMillis(500),
                new PaystackProperties.Retry(3, Duration.ofMillis(10), Duration.ofSeconds(2)));
        var webClient = new PaystackClientConfiguration().paystackWebClient(WebClient.builder(), properties);
        return new PaystackHttpGateway(webClient, properties, JsonMapper.builder().build());
    }

    private static void stubInitialize(int status, String body) {
        paystack.stubFor(post(INITIALIZE).willReturn(json(status, body)));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
