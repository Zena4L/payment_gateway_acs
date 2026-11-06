package org.clement.acs_payment.paystack.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.clement.acs_payment.paystack.PaystackWebhooks;
import org.clement.acs_payment.paystack.WebhookPayload;
import org.clement.acs_payment.paystack.internal.config.PaystackProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PaystackWebhookService implements PaystackWebhooks {

    private static final String ALGORITHM = "HmacSHA512";
    private static final String CHARGE_SUCCESS = "charge.success";

    private final SecretKeySpec signingKey;
    private final JsonMapper json;

    public PaystackWebhookService(PaystackProperties properties, JsonMapper json) {
        this.signingKey = new SecretKeySpec(properties.secretKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.json = json;
    }

    @Override
    public boolean hasValidSignature(byte[] body, @Nullable String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        var expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
        var actual = signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    @Override
    public WebhookPayload parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException ex) {
            return new WebhookPayload.Malformed("Body is not valid JSON");
        }
        var eventType = root.path("event").asString("");
        if (eventType.isBlank()) {
            return new WebhookPayload.Malformed("Missing event type");
        }
        if (!eventType.equals(CHARGE_SUCCESS)) {
            return new WebhookPayload.Unhandled(eventType);
        }
        var data = root.path("data");
        var reference = data.path("reference").asString("");
        var currency = data.path("currency").asString("");
        if (!data.path("id").isNumber() || !data.path("amount").isNumber() || reference.isBlank() || currency.isBlank()) {
            return new WebhookPayload.Malformed("Missing transaction id, reference, amount or currency");
        }
        return new WebhookPayload.ChargeSuccess(eventType, data.path("id").asLong(), reference, data.path("amount").asLong(), currency);
    }

    private byte[] hmac(byte[] body) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(body);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA512 is not available", ex);
        }
    }
}
