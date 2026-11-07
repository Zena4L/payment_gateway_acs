package org.clement.acs_payment.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.TransitionCause;
import org.clement.acs_payment.payment.internal.model.TestActors;
import org.clement.acs_payment.payment.PaymentReconciliation;
import org.clement.acs_payment.payment.ReconciliationReport;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.payment.internal.repository.PaymentTransitionRepository;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class PaymentReconciliationIT {

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(15);
    private static final Duration EXPIRY_CUTOFF = Duration.ofHours(24);

    @Autowired
    PaymentReconciliation reconciliation;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PaystackGateway paystack;

    private Instant now;

    @BeforeEach
    void markExistingPaymentsAsJustReconciled() {
        now = Instant.now();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> jdbc.update("update payment set last_reconciled_at = ?", Timestamp.from(now)));
    }

    @Test
    void closesTheGapWhenSuccessWasNeverRecorded() {
        var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(1)));
        providerReports(payment, ProviderStatus.SUCCESS);

        var report = reconcile(10);

        assertThat(report).isEqualTo(new ReconciliationReport(1, 1, 0, 0));
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).last()
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .containsExactly(PaymentStatus.PENDING, PaymentStatus.SUCCESS, TransitionTrigger.RECONCILER);
    }

    @Test
    void leavesFreshPaymentsAlone() {
        var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofMinutes(5)));

        var report = reconcile(10);

        assertThat(report.claimed()).isZero();
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.PENDING);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void neverClaimsSettledPayments() {
        seed(PaymentStatus.SUCCESS, now.minus(Duration.ofHours(1)));
        seed(PaymentStatus.FAILED, now.minus(Duration.ofHours(1)));
        seed(PaymentStatus.REQUIRES_REVIEW, now.minus(Duration.ofHours(1)));

        assertThat(reconcile(10).claimed()).isZero();
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void failsUnknownPaymentThatPaystackNeverCreated() {
        var payment = seed(PaymentStatus.UNKNOWN, now.minus(Duration.ofHours(1)));
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.NotFound());

        reconcile(10);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void failsInitializingPaymentThatNeverReachedPaystack() {
        var payment = seed(PaymentStatus.INITIALIZING, now.minus(Duration.ofHours(1)));
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.NotFound());

        reconcile(10);

        var saved = payments.findById(payment.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(saved.getGatewayResponse()).isEqualTo("Initialization never reached the provider");
    }

    @Test
    void completesInitializingPaymentThatPaystackCharged() {
        var payment = seed(PaymentStatus.INITIALIZING, now.minus(Duration.ofHours(1)));
        providerReports(payment, ProviderStatus.SUCCESS);

        reconcile(10);

        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus)
                .containsExactly(
                        tuple(null, PaymentStatus.INITIALIZING),
                        tuple(PaymentStatus.INITIALIZING, PaymentStatus.PENDING),
                        tuple(PaymentStatus.PENDING, PaymentStatus.SUCCESS));
    }

    @Test
    void processesAtMostOneBatchPerRun() {
        var stale = List.of(
                seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(3))),
                seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(2))),
                seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(1))));
        stale.forEach(payment -> providerReports(payment, ProviderStatus.PENDING));

        var report = reconcile(2);

        assertThat(report.claimed()).isEqualTo(2);
        verify(paystack, times(1)).verify(stale.get(0).getReference());
        verify(paystack, times(1)).verify(stale.get(1).getReference());
        verify(paystack, never()).verify(stale.get(2).getReference());
    }

    @Test
    void doesNotRecheckPaymentsThatWereJustReconciled() {
        var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(1)));
        providerReports(payment, ProviderStatus.PENDING);

        reconcile(10);
        var second = reconcile(10);

        assertThat(second.claimed()).isZero();
        verify(paystack, times(1)).verify(payment.getReference());
    }

    @Test
    void verifiesEachPaymentOnceWhenRunsOverlap() throws Exception {
        var stale = new ArrayList<Payment>();
        for (int i = 0; i < 6; i++) {
            var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofMinutes(60 + i)));
            when(paystack.verify(payment.getReference())).thenAnswer(invocation -> {
                TimeUnit.MILLISECONDS.sleep(50);
                return found(payment, ProviderStatus.SUCCESS);
            });
            stale.add(payment);
        }

        var start = new CountDownLatch(1);
        var runs = new ArrayList<Future<ReconciliationReport>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                runs.add(executor.submit(() -> {
                    start.await();
                    return reconcile(10);
                }));
            }
            start.countDown();
            var totalClaimed = 0;
            for (var run : runs) {
                totalClaimed += run.get(30, TimeUnit.SECONDS).claimed();
            }
            assertThat(totalClaimed).isEqualTo(6);
        }

        for (var payment : stale) {
            verify(paystack, times(1)).verify(payment.getReference());
            assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                    .filteredOn(transition -> transition.getToStatus() == PaymentStatus.SUCCESS)
                    .hasSize(1);
        }
    }

    @Test
    void stopsTheRunWhenPaystackIsUnavailable() {
        var first = seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(3)));
        var second = seed(PaymentStatus.PENDING, now.minus(Duration.ofHours(2)));
        when(paystack.verify(first.getReference())).thenReturn(new VerificationResult.Unavailable());
        providerReports(second, ProviderStatus.SUCCESS);

        var report = reconcile(10);

        assertThat(report.unavailable()).isEqualTo(1);
        assertThat(statusOf(first)).isEqualTo(PaymentStatus.PENDING);
        assertThat(statusOf(second)).isEqualTo(PaymentStatus.PENDING);
        verify(paystack, never()).verify(second.getReference());
    }

    @Test
    void expiresOverduePaymentThatIsStillUnpaid() {
        var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofDays(2)));
        providerReports(payment, ProviderStatus.PENDING);

        var report = reconcile(10);

        assertThat(report.expired()).isEqualTo(1);
        verify(paystack, times(1)).verify(payment.getReference());
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).last()
                .extracting(PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .containsExactly(PaymentStatus.EXPIRED, TransitionTrigger.RECONCILER);
    }

    @Test
    void expiresOverdueUnknownPaymentThatPaystackStillReportsPending() {
        var payment = seed(PaymentStatus.UNKNOWN, now.minus(Duration.ofDays(2)));
        providerReports(payment, ProviderStatus.PENDING);

        reconcile(10);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void confirmsOverduePaymentThatTurnsOutPaid() {
        var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofDays(2)));
        providerReports(payment, ProviderStatus.SUCCESS);

        var report = reconcile(10);

        assertThat(report.expired()).isZero();
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void doesNotExpireWhenFinalVerificationIsUnavailable() {
        var payment = seed(PaymentStatus.PENDING, now.minus(Duration.ofDays(2)));
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.Unavailable());

        reconcile(10);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.PENDING);
    }

    private ReconciliationReport reconcile(int batchSize) {
        return reconciliation.reconcile(STALE_THRESHOLD, EXPIRY_CUTOFF, batchSize);
    }

    private PaymentStatus statusOf(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow().getStatus();
    }

    private void providerReports(Payment payment, ProviderStatus status) {
        when(paystack.verify(payment.getReference())).thenReturn(found(payment, status));
    }

    private static VerificationResult found(Payment payment, ProviderStatus status) {
        return new VerificationResult.Found(ProviderTransaction.builder()
                .id(4099260516L)
                .status(status)
                .rawStatus(status.name().toLowerCase())
                .reference(payment.getReference())
                .amountMinor(payment.getAmountMinor())
                .currency(payment.getCurrency().getCurrencyCode())
                .channel("card")
                .gatewayResponse("Approved")
                .build());
    }

    private Payment seed(PaymentStatus status, Instant at) {
        var clock = Clock.fixed(at, ZoneOffset.UTC);
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        var payment = Payment.initialize("PAY-" + UUID.randomUUID(), TestActors.of("client-a"), "jane@example.com", money, clock);
        switch (status) {
            case INITIALIZING -> {
            }
            case PENDING -> payment.markInitialized("https://checkout.paystack.com/abc", TestActors.initiatorOf(payment), clock);
            case UNKNOWN -> payment.markOutcomeUnknown(TestActors.initiatorOf(payment), clock);
            case FAILED -> payment.markFailed("Declined", TransitionCause.system(TransitionTrigger.INITIATE), clock);
            case SUCCESS -> {
                payment.markInitialized("https://checkout.paystack.com/abc", TestActors.initiatorOf(payment), clock);
                payment.transitionTo(PaymentStatus.SUCCESS, TransitionCause.system(TransitionTrigger.VERIFY), clock);
            }
            case REQUIRES_REVIEW -> {
                payment.markInitialized("https://checkout.paystack.com/abc", TestActors.initiatorOf(payment), clock);
                payment.flagForReview(TransitionCause.system(TransitionTrigger.VERIFY), clock);
            }
            case EXPIRED -> throw new IllegalArgumentException("Not seeded directly");
        }
        return payments.save(payment);
    }
}
