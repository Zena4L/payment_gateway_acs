package org.clement.acs_payment.webhook.internal.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.clement.acs_payment.paystack.WebhookPayload;
import org.clement.acs_payment.shared.UuidV7;
import org.clement.acs_payment.webhook.events.ProcessingOutcome;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "webhook_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class WebhookEvent {

    private static final int MAX_DIAGNOSTIC = 255;
    private static final int MAX_EVENT_TYPE = 64;
    private static final int MAX_REFERENCE = 64;
    private static final int CURRENCY_LENGTH = 3;
    private static final String UNKNOWN_EVENT_TYPE = "unknown";

    @Id
    private UUID id;

    private @Nullable String dedupKey;

    private String eventType;

    private @Nullable String reference;

    private @Nullable Long providerTransactionId;

    private @Nullable Long amountMinor;

    private @Nullable String currency;

    @Enumerated(EnumType.STRING)
    private ProcessingState processingState;

    private @Nullable String diagnostic;

    private Instant receivedAt;

    private @Nullable Instant processedAt;

    public static String dedupKey(WebhookPayload.ChargeSuccess charge) {
        return charge.eventType() + ":" + charge.transactionId();
    }

    public static WebhookEvent received(WebhookPayload.ChargeSuccess charge, Clock clock) {
        return charge(charge, ProcessingState.RECEIVED, null, clock.instant(), null);
    }

    public static WebhookEvent duplicate(WebhookPayload.ChargeSuccess charge, Clock clock) {
        var now = clock.instant();
        return charge(charge, ProcessingState.DUPLICATE, "Already received; not applied again", now, now);
    }

    public static WebhookEvent ignored(WebhookPayload.Unhandled unhandled, Clock clock) {
        var now = clock.instant();
        return WebhookEvent.builder()
                .id(UuidV7.next())
                .eventType(truncate(unhandled.eventType(), MAX_EVENT_TYPE))
                .processingState(ProcessingState.IGNORED)
                .diagnostic("Event type is not handled")
                .receivedAt(now)
                .processedAt(now)
                .build();
    }

    public static WebhookEvent rejected(WebhookPayload.Malformed malformed, Clock clock) {
        var now = clock.instant();
        return WebhookEvent.builder()
                .id(UuidV7.next())
                .eventType(UNKNOWN_EVENT_TYPE)
                .processingState(ProcessingState.REJECTED)
                .diagnostic(truncate(malformed.reason(), MAX_DIAGNOSTIC))
                .receivedAt(now)
                .processedAt(now)
                .build();
    }

    public void recordOutcome(ProcessingOutcome outcome, String diagnostic, Clock clock) {
        this.processingState = ProcessingState.from(outcome);
        this.diagnostic = truncate(diagnostic, MAX_DIAGNOSTIC);
        this.processedAt = clock.instant();
    }

    private static WebhookEvent charge(WebhookPayload.ChargeSuccess charge, ProcessingState state,
                                       @Nullable String diagnostic, Instant receivedAt, @Nullable Instant processedAt) {
        return WebhookEvent.builder()
                .id(UuidV7.next())
                .dedupKey(dedupKey(charge))
                .eventType(charge.eventType())
                .reference(truncate(charge.reference(), MAX_REFERENCE))
                .providerTransactionId(charge.transactionId())
                .amountMinor(charge.amountMinor())
                .currency(truncate(charge.currency(), CURRENCY_LENGTH))
                .processingState(state)
                .diagnostic(diagnostic)
                .receivedAt(receivedAt)
                .processedAt(processedAt)
                .build();
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
