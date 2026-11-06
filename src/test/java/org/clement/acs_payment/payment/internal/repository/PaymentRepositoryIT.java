package org.clement.acs_payment.payment.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;
import org.clement.acs_payment.TestcontainersConfiguration;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.service.PaymentTransitionRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PaymentTransitionRecorder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRepositoryIT {

    private static final Clock CLOCK = Clock.systemUTC();

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void savesAndReloadsPaymentWithExactAmounts() {
        var payment = payments.save(newPayment(uniqueReference()));

        var reloaded = payments.findByReference(payment.getReference()).orElseThrow();

        assertThat(reloaded.getAmount()).isEqualByComparingTo("150.00");
        assertThat(reloaded.getAmountMinor()).isEqualTo(15000L);
        assertThat(reloaded.getCurrency()).isEqualTo(Currency.getInstance("GHS"));
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.INITIALIZING);
        assertThat(reloaded.getOwnerId()).isEqualTo("client-a");
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void rejectsDuplicateReference() {
        var reference = uniqueReference();
        payments.saveAndFlush(newPayment(reference));

        assertThatThrownBy(() -> payments.saveAndFlush(newPayment(reference)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_payment_reference");
    }

    @Test
    void rejectsStatusOutsideTheWorkingSet() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into payment (id, reference, owner_id, email, amount, amount_minor, currency, status, provider, created_at, updated_at)
                values (?, ?, 'client-a', 'jane@example.com', 150.00, 15000, 'GHS', 'PAID', 'PAYSTACK', now(), now())
                """, UUID.randomUUID(), uniqueReference()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payment_status");
    }

    @Test
    void detectsConcurrentUpdatesWithOptimisticLocking() {
        var saved = payments.save(newPayment(uniqueReference()));
        var first = payments.findById(saved.getId()).orElseThrow();
        var second = payments.findById(saved.getId()).orElseThrow();

        first.transitionTo(PaymentStatus.PENDING, TransitionTrigger.INITIATE, CLOCK);
        payments.saveAndFlush(first);
        second.transitionTo(PaymentStatus.UNKNOWN, TransitionTrigger.INITIATE, CLOCK);

        assertThatThrownBy(() -> payments.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(payments.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void indexesFilteredAndOwnershipColumns() {
        var indexes = jdbc.queryForList("select indexname from pg_indexes where tablename = 'payment'", String.class);

        assertThat(indexes).contains("uk_payment_reference", "ix_payment_owner_created", "ix_payment_status_created",
                "ix_payment_created", "ix_payment_owner_status_created");
    }

    @Test
    void storesTransitionHistoryWithThePayment() {
        var saved = payments.save(newPayment(uniqueReference()));
        var loaded = payments.findById(saved.getId()).orElseThrow();
        loaded.transitionTo(PaymentStatus.PENDING, TransitionTrigger.INITIATE, CLOCK);
        payments.saveAndFlush(loaded);

        var history = transitions.findByPaymentIdOrderByOccurredAtAsc(saved.getId());

        assertThat(history)
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .containsExactly(
                        tuple(null, PaymentStatus.INITIALIZING, TransitionTrigger.INITIATE),
                        tuple(PaymentStatus.INITIALIZING, PaymentStatus.PENDING, TransitionTrigger.INITIATE));
    }

    @Test
    void writesNoHistoryForRejectedTransition() {
        var saved = payments.save(newPayment(uniqueReference()));
        var loaded = payments.findById(saved.getId()).orElseThrow();
        loaded.transitionTo(PaymentStatus.SUCCESS, TransitionTrigger.WEBHOOK, CLOCK);
        payments.saveAndFlush(loaded);

        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(saved.getId())).hasSize(1);
    }

    @Test
    void rollsBackHistoryTogetherWithThePayment() {
        var payment = newPayment(uniqueReference());
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            payments.saveAndFlush(payment);
            assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).hasSize(1);
            throw new IllegalStateException("simulated failure after write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(payments.findById(payment.getId())).isEmpty();
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).isEmpty();
    }

    private static Payment newPayment(String reference) {
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        return Payment.initialize(reference, "client-a", "jane@example.com", money, CLOCK);
    }

    private static String uniqueReference() {
        return "PAY-" + UUID.randomUUID();
    }
}
