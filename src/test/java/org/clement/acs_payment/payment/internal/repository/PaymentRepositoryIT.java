package org.clement.acs_payment.payment.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.EnumSet;
import java.util.UUID;
import org.clement.acs_payment.TestcontainersConfiguration;
import org.clement.acs_payment.payment.internal.model.TransitionCause;
import org.clement.acs_payment.payment.internal.model.TestActors;
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
import org.springframework.data.domain.PageRequest;
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

        first.transitionTo(PaymentStatus.PENDING, TransitionCause.system(TransitionTrigger.INITIATE), CLOCK);
        payments.saveAndFlush(first);
        second.transitionTo(PaymentStatus.UNKNOWN, TransitionCause.system(TransitionTrigger.INITIATE), CLOCK);

        assertThatThrownBy(() -> payments.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(payments.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void indexesFilteredAndOwnershipColumns() {
        var indexes = jdbc.queryForList("select indexname from pg_indexes where tablename = 'payment'", String.class);

        assertThat(indexes).contains("uk_payment_reference", "ix_payment_owner_created", "ix_payment_status_created",
                "ix_payment_created", "ix_payment_owner_status_created", "ix_payment_reconcile");
    }

    @Test
    void storesTransitionHistoryWithThePayment() {
        var saved = payments.save(newPayment(uniqueReference()));
        var loaded = payments.findById(saved.getId()).orElseThrow();
        loaded.transitionTo(PaymentStatus.PENDING, TransitionCause.system(TransitionTrigger.INITIATE), CLOCK);
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
        loaded.transitionTo(PaymentStatus.SUCCESS, TransitionCause.system(TransitionTrigger.WEBHOOK), CLOCK);
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

    @Test
    void requiresActorAndReasonOnAdminTransitions() {
        var saved = payments.save(newPayment(uniqueReference()));

        assertThatThrownBy(() -> jdbc.update("""
                insert into payment_transition (id, payment_id, from_status, to_status, triggered_by, occurred_at)
                values (?, ?, 'REQUIRES_REVIEW', 'SUCCESS', 'ADMIN', now())
                """, UUID.randomUUID(), saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transition_admin_details");
    }

    @Test
    void refusesActorKeyIdWithoutActor() {
        var saved = payments.save(newPayment(uniqueReference()));

        assertThatThrownBy(() -> jdbc.update("""
                insert into payment_transition (id, payment_id, from_status, to_status, triggered_by, occurred_at, actor_key_id)
                values (?, ?, 'INITIALIZING', 'PENDING', 'INITIATE', now(), 'a1b2c3d4e5f6')
                """, UUID.randomUUID(), saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transition_actor_key_id");
    }

    @Test
    void storesActorAndKeyIdOfTheInitiator() {
        var saved = payments.saveAndFlush(newPayment(uniqueReference()));

        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(saved.getId()))
                .extracting(PaymentTransition::getActor, PaymentTransition::getActorKeyId)
                .containsExactly(tuple("client-a", TestActors.KEY_ID));
    }

    @Test
    void allowsSystemTransitionsWithoutActorOrReason() {
        var saved = payments.save(newPayment(uniqueReference()));

        var inserted = new TransactionTemplate(transactionManager).execute(status -> jdbc.update("""
                insert into payment_transition (id, payment_id, from_status, to_status, triggered_by, occurred_at)
                values (?, ?, 'INITIALIZING', 'PENDING', 'INITIATE', now())
                """, UUID.randomUUID(), saved.getId()));

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void findsOnlyLiveDuplicatesOfTheSameIntentWithinTheWindowNewestFirst() {
        var now = Instant.now();
        var since = now.minus(Duration.ofSeconds(60));
        var email = "buyer-" + UUID.randomUUID() + "@example.com";
        var older = seed("client-a", email, "150.00", now.minusSeconds(30));
        var newer = seed("client-a", email, "150.00", now.minusSeconds(10));
        var own = seed("client-a", email, "150.00", now.minusSeconds(5));
        var failed = seed("client-a", email, "150.00", now.minusSeconds(4));
        var expired = seed("client-a", email, "150.00", now.minusSeconds(3));
        seed("client-a", email, "150.00", now.minusSeconds(61));
        seed("client-b", email, "150.00", now.minusSeconds(2));
        seed("client-a", email, "151.00", now.minusSeconds(2));
        seed("client-a", "other-" + email, "150.00", now.minusSeconds(2));
        forceStatus(failed, PaymentStatus.FAILED);
        forceStatus(expired, PaymentStatus.EXPIRED);

        var found = payments.findLiveDuplicates("client-a", email, 15000L, Currency.getInstance("GHS"), own.getReference(), since,
                EnumSet.of(PaymentStatus.FAILED, PaymentStatus.EXPIRED), PageRequest.of(0, 10));

        assertThat(found).extracting(Payment::getReference).containsExactly(newer.getReference(), older.getReference());
    }

    @Test
    void takesIntentLockInsideTransaction() {
        var result = new TransactionTemplate(transactionManager).execute(status -> payments.lockIntent("client-a|jane@example.com|15000|GHS"));

        assertThat(result).isEqualTo(1L);
    }

    private Payment seed(String ownerId, String email, String amount, Instant createdAt) {
        var money = new Money(new BigDecimal(amount), Currency.getInstance("GHS"));
        return payments.saveAndFlush(Payment.initialize(uniqueReference(), TestActors.of(ownerId), email, money, Clock.fixed(createdAt, ZoneOffset.UTC)));
    }

    private void forceStatus(Payment payment, PaymentStatus status) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                tx -> jdbc.update("update payment set status = ? where id = ?", status.name(), payment.getId()));
    }

    private static Payment newPayment(String reference) {
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        return Payment.initialize(reference, TestActors.of("client-a"), "jane@example.com", money, CLOCK);
    }

    private static String uniqueReference() {
        return "PAY-" + UUID.randomUUID();
    }
}
