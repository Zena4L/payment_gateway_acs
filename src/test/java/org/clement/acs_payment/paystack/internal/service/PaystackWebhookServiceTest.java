package org.clement.acs_payment.paystack.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.clement.acs_payment.paystack.WebhookPayload;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PaystackWebhookServiceTest {

    private static final String SECRET = "sk_test_webhook";
    private static final byte[] CHARGE = """
            {"event":"charge.success","data":{"id":4099260516,"reference":"PAY-1","amount":15000,"currency":"GHS","status":"success"}}
            """.getBytes(StandardCharsets.UTF_8);

    private final PaystackWebhookService webhooks = new PaystackWebhookService(
            new PaystackProperties(URI.create("https://api.paystack.co"), SECRET, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    new PaystackProperties.Retry(3, Duration.ofMillis(10), Duration.ofSeconds(2))),
            JsonMapper.builder().build());

    @Test
    void acceptsSignatureComputedOverRawBody() {
        assertThat(webhooks.hasValidSignature(CHARGE, sign(CHARGE))).isTrue();
    }

    @Test
    void acceptsUppercaseHexSignature() {
        assertThat(webhooks.hasValidSignature(CHARGE, sign(CHARGE).toUpperCase(Locale.ROOT))).isTrue();
    }

    @Test
    void rejectsMissingSignature() {
        assertThat(webhooks.hasValidSignature(CHARGE, null)).isFalse();
        assertThat(webhooks.hasValidSignature(CHARGE, "")).isFalse();
    }

    @Test
    void rejectsSignatureFromAnotherSecret() {
        assertThat(webhooks.hasValidSignature(CHARGE, sign(CHARGE, "sk_test_other"))).isFalse();
    }

    @Test
    void rejectsBodyChangedAfterSigning() {
        var signature = sign(CHARGE);
        var tampered = new String(CHARGE, StandardCharsets.UTF_8).replace("15000", "1").getBytes(StandardCharsets.UTF_8);

        assertThat(webhooks.hasValidSignature(tampered, signature)).isFalse();
    }

    @Test
    void parsesChargeSuccess() {
        assertThat(webhooks.parse(CHARGE))
                .isEqualTo(new WebhookPayload.ChargeSuccess("charge.success", 4099260516L, "PAY-1", 15000, "GHS"));
    }

    @Test
    void reportsOtherEventTypesAsUnhandled() {
        var transfer = "{\"event\":\"transfer.success\",\"data\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);

        assertThat(webhooks.parse(transfer)).isEqualTo(new WebhookPayload.Unhandled("transfer.success"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{}",
            "{\"event\":\"charge.success\",\"data\":{\"reference\":\"PAY-1\",\"amount\":15000,\"currency\":\"GHS\"}}",
            "{\"event\":\"charge.success\",\"data\":{\"id\":1,\"amount\":15000,\"currency\":\"GHS\"}}",
            "{\"event\":\"charge.success\",\"data\":{\"id\":1,\"reference\":\"PAY-1\",\"currency\":\"GHS\"}}",
            "{\"event\":\"charge.success\",\"data\":{\"id\":1,\"reference\":\"PAY-1\",\"amount\":15000}}"
    })
    void reportsUnusablePayloadAsMalformed(String body) {
        assertThat(webhooks.parse(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(WebhookPayload.Malformed.class);
    }

    private static String sign(byte[] body) {
        return sign(body, SECRET);
    }

    private static String sign(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
