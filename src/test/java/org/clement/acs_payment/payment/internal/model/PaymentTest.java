package org.clement.acs_payment.payment.internal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import org.clement.acs_payment.payment.internal.exception.InvalidTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

    private static final Instant CREATED = Instant.parse("2026-11-06T10:00:00Z");
    private static final Clock CREATED_CLOCK = Clock.fixed(CREATED, ZoneOffset.UTC);
    private static final Clock LATER_CLOCK = Clock.offset(CREATED_CLOCK, Duration.ofMinutes(5));

    @Test
    void startsInitializingWithCreationRecordedInHistory() {
        var payment = newPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIALIZING);
        assertThat(payment.getAmountMinor()).isEqualTo(15000L);
        assertThat(payment.getCreatedAt()).isEqualTo(CREATED);
        assertThat(payment.pendingTransitions())
                .extracting(PaymentTransitioned::from, PaymentTransitioned::to, PaymentTransitioned::trigger, PaymentTransitioned::occurredAt)
                .containsExactly(tuple(null, PaymentStatus.INITIALIZING, TransitionTrigger.INITIATE, CREATED));
    }

    @Test
    void appliesAllowedTransitionAndRecordsIt() {
        var payment = newPayment();

        var result = payment.transitionTo(PaymentStatus.PENDING, TransitionTrigger.INITIATE, LATER_CLOCK);

        assertThat(result).isEqualTo(TransitionResult.APPLIED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getUpdatedAt()).isEqualTo(LATER_CLOCK.instant());
        assertThat(payment.pendingTransitions()).last()
                .extracting(PaymentTransitioned::from, PaymentTransitioned::to, PaymentTransitioned::trigger, PaymentTransitioned::occurredAt)
                .containsExactly(PaymentStatus.INITIALIZING, PaymentStatus.PENDING, TransitionTrigger.INITIATE, LATER_CLOCK.instant());
    }

    @Test
    void treatsSameStateAsNoOpWithoutHistory() {
        var payment = pendingPayment();

        var result = payment.transitionTo(PaymentStatus.PENDING, TransitionTrigger.VERIFY, LATER_CLOCK);

        assertThat(result).isEqualTo(TransitionResult.NO_OP);
        assertThat(payment.pendingTransitions()).hasSize(2);
    }

    @Test
    void rejectsDisallowedTransitionAndLeavesPaymentUnchanged() {
        var payment = newPayment();
        var updatedAt = payment.getUpdatedAt();

        var result = payment.transitionTo(PaymentStatus.SUCCESS, TransitionTrigger.WEBHOOK, LATER_CLOCK);

        assertThat(result).isEqualTo(TransitionResult.REJECTED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIALIZING);
        assertThat(payment.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(payment.pendingTransitions()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "UNKNOWN", "REQUIRES_REVIEW", "EXPIRED"})
    void neverMovesASuccessfulPayment(PaymentStatus next) {
        var payment = pendingPayment();
        payment.transitionTo(PaymentStatus.SUCCESS, TransitionTrigger.VERIFY, LATER_CLOCK);

        var result = payment.transitionTo(next, TransitionTrigger.WEBHOOK, LATER_CLOCK);

        assertThat(result).isEqualTo(TransitionResult.REJECTED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void lateFailureCannotUndoSuccess() {
        var payment = pendingPayment();
        payment.transitionTo(PaymentStatus.SUCCESS, TransitionTrigger.VERIFY, LATER_CLOCK);

        var result = payment.transitionTo(PaymentStatus.FAILED, TransitionTrigger.WEBHOOK, LATER_CLOCK);

        assertThat(result).isEqualTo(TransitionResult.REJECTED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "FAILED"})
    void reviewNeverResolvesAutomatically(PaymentStatus outcome) {
        var payment = paymentInReview();

        var result = payment.transitionTo(outcome, TransitionTrigger.VERIFY, LATER_CLOCK);

        assertThat(result).isEqualTo(TransitionResult.REJECTED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
    }

    @Test
    void resolvesReviewThroughExplicitAdminAction() {
        var payment = paymentInReview();

        payment.resolveReview(PaymentStatus.SUCCESS, LATER_CLOCK);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.pendingTransitions()).last()
                .extracting(PaymentTransitioned::trigger)
                .isEqualTo(TransitionTrigger.ADMIN);
    }

    @Test
    void refusesReviewResolutionWhenNotInReview() {
        var payment = pendingPayment();

        assertThatThrownBy(() -> payment.resolveReview(PaymentStatus.SUCCESS, LATER_CLOCK))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void refusesReviewResolutionToNonFinalOutcome() {
        var payment = paymentInReview();

        assertThatThrownBy(() -> payment.resolveReview(PaymentStatus.PENDING, LATER_CLOCK))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
    }

    private static Payment newPayment() {
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        return Payment.initialize("PAY-1", "client-a", "jane@example.com", money, CREATED_CLOCK);
    }

    private static Payment pendingPayment() {
        var payment = newPayment();
        payment.transitionTo(PaymentStatus.PENDING, TransitionTrigger.INITIATE, CREATED_CLOCK);
        return payment;
    }

    private static Payment paymentInReview() {
        var payment = pendingPayment();
        payment.transitionTo(PaymentStatus.REQUIRES_REVIEW, TransitionTrigger.VERIFY, CREATED_CLOCK);
        return payment;
    }
}
