package org.clement.acs_payment.paystack.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.clement.acs_payment.SandboxKey;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.InitializeTransaction;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.paystack.internal.config.PaystackClientConfiguration;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

class PaystackSandboxContractE2E {

    private static PaystackHttpGateway paystack;

    @BeforeAll
    static void connectToSandbox() {
        paystack = gateway(SandboxKey.secretKey());
    }

    @Test
    void initializesTransactionAndReturnsHostedCheckout() {
        var result = paystack.initialize(transaction(reference()));

        assertThat(result).isInstanceOfSatisfying(InitializationResult.Initialized.class,
                initialized -> assertThat(initialized.authorizationUrl()).startsWith("https://checkout.paystack.com/"));
    }

    @Test
    void reportsReusedReferenceAsDuplicate() {
        var reference = reference();
        assertThat(paystack.initialize(transaction(reference))).isInstanceOf(InitializationResult.Initialized.class);

        var second = paystack.initialize(transaction(reference));

        assertThat(second).isInstanceOfSatisfying(InitializationResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo(RejectionReason.DUPLICATE_REFERENCE));
    }

    @Test
    void verifiesUnpaidTransactionAsAbandonedAndPending() {
        var reference = reference();
        assertThat(paystack.initialize(transaction(reference))).isInstanceOf(InitializationResult.Initialized.class);

        var result = paystack.verify(reference);

        assertThat(result).isInstanceOfSatisfying(VerificationResult.Found.class, found -> {
            assertThat(found.transaction().rawStatus()).isEqualTo("abandoned");
            assertThat(found.transaction().status()).isEqualTo(ProviderStatus.PENDING);
            assertThat(found.transaction().reference()).isEqualTo(reference);
            assertThat(found.transaction().amountMinor()).isEqualTo(15000L);
            assertThat(found.transaction().currency()).isEqualTo("GHS");
        });
    }

    @Test
    void reportsUnknownReferenceAsNotFound() {
        assertThat(paystack.verify(reference())).isInstanceOf(VerificationResult.NotFound.class);
    }

    @Test
    void reportsInvalidKeyAsUnauthorized() {
        var withBadKey = gateway("sk_test_" + "0".repeat(40));

        var result = withBadKey.initialize(transaction(reference()));

        assertThat(result).isInstanceOfSatisfying(InitializationResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo(RejectionReason.UNAUTHORIZED));
    }

    private static PaystackHttpGateway gateway(String secretKey) {
        var properties = new PaystackProperties(URI.create(SandboxKey.PAYSTACK_BASE_URL), secretKey,
                Duration.ofSeconds(5), Duration.ofSeconds(20), new PaystackProperties.Retry(3, Duration.ofMillis(500), Duration.ofSeconds(5)));
        var webClient = new PaystackClientConfiguration().paystackWebClient(WebClient.builder(), properties);
        return new PaystackHttpGateway(webClient, properties, JsonMapper.builder().build());
    }

    private static InitializeTransaction transaction(String reference) {
        return InitializeTransaction.builder()
                .reference(reference)
                .email("e2e@example.com")
                .amountMinor(15000)
                .currency("GHS")
                .build();
    }

    private static String reference() {
        return "E2E-" + UUID.randomUUID();
    }
}
