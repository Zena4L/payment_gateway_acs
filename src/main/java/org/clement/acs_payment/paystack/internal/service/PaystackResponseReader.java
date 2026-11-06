package org.clement.acs_payment.paystack.internal.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
class PaystackResponseReader {

    private static final int MAX_STORED_TEXT = 255;
    private static final Set<String> PENDING_STATUSES = Set.of("abandoned", "ongoing", "pending", "processing", "queued");

    private final JsonMapper json;

    Optional<String> authorizationUrl(String body) {
        var root = parse(body);
        var url = root.path("data").path("authorization_url").asString("");
        return root.path("status").asBoolean(false) && !url.isBlank() ? Optional.of(url) : Optional.empty();
    }

    Optional<ProviderTransaction> transaction(String body) {
        var root = parse(body);
        var data = root.path("data");
        var rawStatus = data.path("status").asString("");
        var reference = data.path("reference").asString("");
        var currency = data.path("currency").asString("");
        if (!root.path("status").asBoolean(false) || !data.path("id").isNumber() || !data.path("amount").isNumber()
                || rawStatus.isBlank() || reference.isBlank() || currency.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ProviderTransaction.builder()
                .id(data.path("id").asLong())
                .status(providerStatus(rawStatus))
                .rawStatus(rawStatus)
                .reference(reference)
                .amountMinor(data.path("amount").asLong())
                .currency(currency)
                .channel(optionalText(data.path("channel")))
                .paidAt(instant(data.path("paid_at")))
                .gatewayResponse(truncate(optionalText(data.path("gateway_response"))))
                .build());
    }

    String message(String body) {
        var message = parse(body).path("message").asString("").strip();
        return message.length() > MAX_STORED_TEXT ? message.substring(0, MAX_STORED_TEXT) : message;
    }

    static ProviderStatus providerStatus(String rawStatus) {
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
        return text != null && text.length() > MAX_STORED_TEXT ? text.substring(0, MAX_STORED_TEXT) : text;
    }
}
