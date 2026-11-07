package org.clement.acs_payment.payment.internal.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.payment.internal.exception.InvalidTransitionException;
import org.clement.acs_payment.shared.UuidV7;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

@Slf4j
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Payment {

    private static final String PROVIDER = "PAYSTACK";
    private static final int MAX_GATEWAY_RESPONSE = 255;

    @Id
    private UUID id;

    private String reference;

    private String ownerId;

    private String email;

    private BigDecimal amount;

    private long amountMinor;

    private Currency currency;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String provider;

    private @Nullable Long providerTransactionId;

    private @Nullable String channel;

    private @Nullable String authorizationUrl;

    private @Nullable String gatewayResponse;

    private @Nullable Instant paidAt;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private @Nullable Long version;

    @Transient
    @Getter(AccessLevel.NONE)
    private final List<PaymentTransitioned> pendingTransitions = new ArrayList<>();

    public static Payment initialize(String reference, Actor initiator, String email, Money money, Clock clock) {
        var now = clock.instant();
        var payment = Payment.builder()
                .id(UuidV7.next())
                .reference(reference)
                .ownerId(initiator.ownerId())
                .email(normalizeEmail(email))
                .amount(money.amount())
                .amountMinor(money.minorUnits())
                .currency(money.currency())
                .provider(PROVIDER)
                .status(PaymentStatus.INITIALIZING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        payment.record(null, PaymentStatus.INITIALIZING, TransitionCause.by(initiator, TransitionTrigger.INITIATE), now, null);
        return payment;
    }

    public boolean matches(String ownerId, String email, Money money) {
        return this.ownerId.equals(ownerId)
                && this.email.equals(normalizeEmail(email))
                && this.currency.equals(money.currency())
                && this.amount.compareTo(money.amount()) == 0;
    }

    public String intentKey() {
        return String.join("|", ownerId, email, Long.toString(amountMinor), currency.getCurrencyCode());
    }

    public TransitionResult markInitialized(String authorizationUrl, Actor initiator, Clock clock) {
        var result = transitionTo(PaymentStatus.PENDING, TransitionCause.by(initiator, TransitionTrigger.INITIATE), clock);
        if (result == TransitionResult.APPLIED) {
            this.authorizationUrl = authorizationUrl;
        }
        return result;
    }

    public TransitionResult markOutcomeUnknown(Actor initiator, Clock clock) {
        return transitionTo(PaymentStatus.UNKNOWN, TransitionCause.by(initiator, TransitionTrigger.INITIATE), clock);
    }

    public TransitionResult markFailed(String reason, TransitionCause cause, Clock clock) {
        var result = transitionTo(PaymentStatus.FAILED, cause, clock);
        if (result == TransitionResult.APPLIED) {
            this.gatewayResponse = truncate(reason);
        }
        return result;
    }

    public TransitionResult markFailed(ProviderDetails details, TransitionCause cause, Clock clock) {
        var result = transitionTo(PaymentStatus.FAILED, cause, clock);
        if (result == TransitionResult.APPLIED) {
            recordProviderDetails(details);
        }
        return result;
    }

    public TransitionResult markSucceeded(ProviderDetails details, TransitionCause cause, Clock clock) {
        var result = transitionTo(PaymentStatus.SUCCESS, cause, clock);
        if (result == TransitionResult.APPLIED) {
            recordProviderDetails(details);
        }
        return result;
    }

    public TransitionResult flagForReview(TransitionCause cause, Clock clock) {
        return transitionTo(PaymentStatus.REQUIRES_REVIEW, cause, clock);
    }

    public boolean matchesProviderRecord(String reference, long amountMinor, String currencyCode) {
        return this.reference.equals(reference) && matchesAmount(amountMinor, currencyCode);
    }

    public boolean matchesAmount(long amountMinor, String currencyCode) {
        return this.amountMinor == amountMinor && this.currency.getCurrencyCode().equals(currencyCode);
    }

    public TransitionResult transitionTo(PaymentStatus next, TransitionCause cause, Clock clock) {
        if (status == next) {
            return TransitionResult.NO_OP;
        }
        if (status == PaymentStatus.REQUIRES_REVIEW || !status.canMoveTo(next)) {
            log.warn("Rejected payment transition {} -> {} for reference {} triggered by {}", status, next, reference, cause.trigger());
            return TransitionResult.REJECTED;
        }
        apply(next, cause, clock);
        return TransitionResult.APPLIED;
    }

    public void resolveReview(PaymentStatus outcome, Actor admin, String reason, Clock clock) {
        if (status != PaymentStatus.REQUIRES_REVIEW || !status.canMoveTo(outcome)) {
            throw new InvalidTransitionException(status, outcome);
        }
        var now = clock.instant();
        record(status, outcome, TransitionCause.by(admin, TransitionTrigger.ADMIN), now, reason);
        status = outcome;
        updatedAt = now;
    }

    @DomainEvents
    public List<PaymentTransitioned> pendingTransitions() {
        return List.copyOf(pendingTransitions);
    }

    @AfterDomainEventPublication
    void clearPendingTransitions() {
        pendingTransitions.clear();
    }

    private void apply(PaymentStatus next, TransitionCause cause, Clock clock) {
        var now = clock.instant();
        record(status, next, cause, now, null);
        status = next;
        updatedAt = now;
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private void recordProviderDetails(ProviderDetails details) {
        this.providerTransactionId = details.transactionId();
        this.channel = details.channel();
        this.paidAt = details.paidAt();
        var response = details.gatewayResponse();
        this.gatewayResponse = response == null ? null : truncate(response);
    }

    private static String truncate(String text) {
        return text.length() > MAX_GATEWAY_RESPONSE ? text.substring(0, MAX_GATEWAY_RESPONSE) : text;
    }

    private void record(@Nullable PaymentStatus from, PaymentStatus to, TransitionCause cause, Instant at,
                        @Nullable String reason) {
        var actor = cause.actor();
        pendingTransitions.add(PaymentTransitioned.builder()
                .paymentId(id)
                .from(from)
                .to(to)
                .trigger(cause.trigger())
                .occurredAt(at)
                .actor(actor == null ? null : actor.ownerId())
                .actorKeyId(actor == null ? null : actor.keyId())
                .reason(reason)
                .build());
    }
}
