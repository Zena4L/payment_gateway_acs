package org.clement.acs_payment.paystack.internal.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.paystack.internal.config.PaystackClientConfiguration;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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

        assertThat(result).isEqualTo(new InitializationResult.Initialized("https://checkout.paystack.com/abc", "abc"));
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

    private static PaystackHttpGateway gateway(String baseUrl) {
        var properties = new PaystackProperties(
                URI.create(baseUrl),
                SECRET,
                Duration.ofMillis(500),
                Duration.ofMillis(500),
                new PaystackProperties.Retry(3, Duration.ofMillis(10)));
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
