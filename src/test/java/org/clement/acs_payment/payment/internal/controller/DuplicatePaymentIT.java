package org.clement.acs_payment.payment.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.exception.DuplicatePaymentException;
import org.clement.acs_payment.payment.internal.exception.PaymentConflictException;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.payment.internal.repository.PaymentTransitionRepository;
import org.clement.acs_payment.payment.internal.service.InitiatePaymentCommand;
import org.clement.acs_payment.payment.internal.service.PaymentInitiation;
import org.clement.acs_payment.payment.internal.service.PaymentInitiationService;
import org.clement.acs_payment.paystack.InitializationResult;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.RejectionReason;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@TestPropertySource(properties = "acs.payment.duplicate-window=60s")
class DuplicatePaymentIT {

    private static final String PAYMENTS = "/api/v1/payments/initiate";
    private static final String CHECKOUT_URL = "https://checkout.paystack.com/abc";
    private static final InitializationResult INITIALIZED = new InitializationResult.Initialized(CHECKOUT_URL);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    PaymentInitiationService initiation;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PaystackGateway paystack;

    @Test
    void rejectsSameDetailsUnderNewReferenceWithinWindow() throws Exception {
        var email = uniqueEmail();
        var first = uniqueReference();
        var second = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(first, email, "150.00")).andExpect(status().isCreated());
        var existing = payments.findByReference(first).orElseThrow();

        initiate("client-a", body(second, email, "150.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-payment"))
                .andExpect(jsonPath("$.existingReference").value(first))
                .andExpect(jsonPath("$.existingTransactionId").value(existing.getId().toString()))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        assertThat(rowsFor(email)).isEqualTo(1L);
        assertThat(payments.findByReference(second)).isEmpty();
        verify(paystack, times(1)).initialize(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"INITIALIZING", "PENDING", "UNKNOWN", "SUCCESS", "REQUIRES_REVIEW"})
    void blocksWhileEarlierPaymentIsLiveOrPaid(PaymentStatus earlierStatus) throws Exception {
        var email = uniqueEmail();
        var first = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(first, email, "150.00")).andExpect(status().isCreated());
        forceStatus(first, earlierStatus);

        initiate("client-a", body(uniqueReference(), email, "150.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-payment"))
                .andExpect(jsonPath("$.existingReference").value(first));

        assertThat(rowsFor(email)).isEqualTo(1L);
        verify(paystack, times(1)).initialize(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "EXPIRED"})
    void allowsNewReferenceOnceEarlierPaymentClosedUnpaid(PaymentStatus closedStatus) throws Exception {
        var email = uniqueEmail();
        var first = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(first, email, "150.00")).andExpect(status().isCreated());
        forceStatus(first, closedStatus);

        initiate("client-a", body(uniqueReference(), email, "150.00")).andExpect(status().isCreated());

        assertThat(rowsFor(email)).isEqualTo(2L);
        verify(paystack, times(2)).initialize(any());
    }

    @Test
    void allowsRetryAfterProviderRejectedTheEarlierPayment() throws Exception {
        var email = uniqueEmail();
        var first = uniqueReference();
        when(paystack.initialize(any()))
                .thenReturn(new InitializationResult.Rejected(RejectionReason.BAD_REQUEST, "Invalid amount"))
                .thenReturn(INITIALIZED);
        initiate("client-a", body(first, email, "150.00")).andExpect(status().isBadGateway());

        initiate("client-a", body(uniqueReference(), email, "150.00")).andExpect(status().isCreated());

        assertThat(payments.findByReference(first).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rowsFor(email)).isEqualTo(2L);
    }

    @Test
    void allowsSameDetailsOnceWindowHasPassed() throws Exception {
        var email = uniqueEmail();
        var first = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(first, email, "150.00")).andExpect(status().isCreated());
        backdate(first, Duration.ofSeconds(61));

        initiate("client-a", body(uniqueReference(), email, "150.00")).andExpect(status().isCreated());

        assertThat(rowsFor(email)).isEqualTo(2L);
    }

    @Test
    void rejectedDuplicateLeavesNothingBehindAndSucceedsAfterWindow() throws Exception {
        var email = uniqueEmail();
        var first = uniqueReference();
        var second = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(first, email, "150.00")).andExpect(status().isCreated());
        initiate("client-a", body(second, email, "150.00")).andExpect(status().isConflict());
        assertThat(payments.findByReference(second)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from payment_transition t join payment p on p.id = t.payment_id where p.reference = ?",
                Long.class, second)).isZero();

        backdate(first, Duration.ofSeconds(61));

        initiate("client-a", body(second, email, "150.00")).andExpect(status().isCreated());
        assertThat(payments.findByReference(second)).isPresent();
        verify(paystack, times(2)).initialize(any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "different amount | client-a | SAME        | 151.00",
            "different email  | client-a | OTHER       | 150.00",
            "different owner  | client-b | SAME        | 150.00"
    })
    void allowsNewReferenceWhenDetailsDiffer(String scenario, String owner, String emailChoice, String amount) throws Exception {
        var email = uniqueEmail();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(uniqueReference(), email, "150.00")).andExpect(status().isCreated());
        var secondEmail = emailChoice.equals("SAME") ? email : uniqueEmail();

        initiate(owner, body(uniqueReference(), secondEmail, amount)).andExpect(status().isCreated());

        verify(paystack, times(2)).initialize(any());
    }

    @Test
    void replaysIdenticalRequestManyTimesWithOnePaymentAndOneProviderCall() throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, email, "150.00")).andExpect(status().isCreated());
        var id = payments.findByReference(reference).orElseThrow().getId();

        for (int i = 0; i < 4; i++) {
            initiate("client-a", body(reference, email, "150.00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reference").value(reference))
                    .andExpect(jsonPath("$.authorizationUrl").value(CHECKOUT_URL))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        assertThat(rowsFor(email)).isEqualTo(1L);
        assertThat(payments.findByReference(reference).orElseThrow().getId()).isEqualTo(id);
        assertThat(historyOf(reference)).containsExactly(PaymentStatus.INITIALIZING, PaymentStatus.PENDING);
        verify(paystack, times(1)).initialize(any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "email in another case | UPPER | 150.00",
            "amount without scale  | SAME  | 150",
            "amount with one digit | SAME  | 150.0"
    })
    void treatsEquivalentRequestAsReplay(String scenario, String emailForm, String amount) throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, email, "150.00")).andExpect(status().isCreated());
        var replayEmail = emailForm.equals("UPPER") ? email.toUpperCase() : email;

        initiate("client-a", body(reference, replayEmail, amount))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference));

        assertThat(rowsFor(email)).isEqualTo(1L);
        verify(paystack, times(1)).initialize(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"UNKNOWN", "SUCCESS", "FAILED", "EXPIRED", "REQUIRES_REVIEW"})
    void replayReturnsPaymentAsItIsWithoutTouchingIt(PaymentStatus state) throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, email, "150.00")).andExpect(status().isCreated());
        forceStatus(reference, state);
        var before = payments.findByReference(reference).orElseThrow();
        var historyBefore = historyOf(reference);

        initiate("client-a", body(reference, email, "150.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(state.name()));

        var after = payments.findByReference(reference).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(historyOf(reference)).isEqualTo(historyBefore);
        verify(paystack, times(1)).initialize(any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "different amount | SAME  | 200.00",
            "different email  | OTHER | 150.00"
    })
    void rejectsChangedDetailsOnSameReferenceAndLeavesPaymentUntouched(String scenario, String emailChoice, String amount) throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, email, "150.00")).andExpect(status().isCreated());
        var before = payments.findByReference(reference).orElseThrow();
        var secondEmail = emailChoice.equals("SAME") ? email : uniqueEmail();

        initiate("client-a", body(reference, secondEmail, amount))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payment-conflict"));

        var after = payments.findByReference(reference).orElseThrow();
        assertThat(after.getAmount()).isEqualByComparingTo(before.getAmount());
        assertThat(after.getEmail()).isEqualTo(before.getEmail());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(historyOf(reference)).containsExactly(PaymentStatus.INITIALIZING, PaymentStatus.PENDING);
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void rejectsAnotherClientsReferenceWithoutExposingTheirPayment() throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, email, "150.00")).andExpect(status().isCreated());
        var owned = payments.findByReference(reference).orElseThrow();

        var response = initiate("client-b", body(reference, email, "150.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payment-conflict"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(owned.getId().toString()).doesNotContain(email).doesNotContain(CHECKOUT_URL);
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void createsOnePaymentForConcurrentRequestsUnderNewReferences() throws Exception {
        var email = uniqueEmail();
        slowInitialize();

        var results = runConcurrently(10, i -> command("client-a", uniqueReference(), email, "150.00"));

        assertThat(rowsFor(email)).isEqualTo(1L);
        assertThat(results).filteredOn(PaymentInitiation.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(DuplicatePaymentException.class::isInstance).hasSize(9);
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void createsOnePaymentForConcurrentIdenticalRequestsWithGuardOn() throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        slowInitialize();

        var results = runConcurrently(10, i -> command("client-a", reference, email, "150.00"));

        assertThat(rowsFor(email)).isEqualTo(1L);
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PaymentInitiation.class));
        assertThat(results).filteredOn(result -> !((PaymentInitiation) result).replayed()).hasSize(1);
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void mixedBurstForOnePurchaseNeverCreatesTwoPaymentsOrFails() throws Exception {
        var email = uniqueEmail();
        var reference = uniqueReference();
        slowInitialize();

        var results = runConcurrently(12, i -> {
            if (i < 5) {
                return command("client-a", reference, email, "150.00");
            }
            if (i < 8) {
                return command("client-a", reference, email, "200.00");
            }
            return command("client-a", uniqueReference(), email, "150.00");
        });

        var created = results.stream().filter(result -> result instanceof PaymentInitiation initiation && !initiation.replayed()).count();
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfAny(
                PaymentInitiation.class, PaymentConflictException.class, DuplicatePaymentException.class));
        assertThat(rowsFor(email, 15000L)).isEqualTo(1L);
        assertThat(rowsFor(email, 20000L)).isLessThanOrEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from payment where reference = ?", Long.class, reference)).isEqualTo(1L);
        assertThat(rowsFor(email)).isEqualTo(created);
        verify(paystack, times((int) created)).initialize(any());
    }

    @Test
    void doesNotSerialiseDifferentPurchasesFromSameClient() throws Exception {
        var email = uniqueEmail();
        slowInitialize();

        var results = runConcurrently(2, i -> command("client-a", uniqueReference(), email, i == 0 ? "150.00" : "175.00"));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PaymentInitiation.class));
        assertThat(rowsFor(email)).isEqualTo(2L);
        verify(paystack, times(2)).initialize(any());
    }

    @Test
    void neverCallsPaystackForRejectedDuplicate() throws Exception {
        var email = uniqueEmail();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(uniqueReference(), email, "150.00")).andExpect(status().isCreated());
        clearInvocations(paystack);

        initiate("client-a", body(uniqueReference(), email, "150.00")).andExpect(status().isConflict());

        verify(paystack, never()).initialize(any());
    }

    private void slowInitialize() {
        when(paystack.initialize(any())).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(300);
            return INITIALIZED;
        });
    }

    private void forceStatus(String reference, PaymentStatus status) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                tx -> jdbc.update("update payment set status = ? where reference = ?", status.name(), reference));
    }

    private void backdate(String reference, Duration age) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                tx -> jdbc.update("update payment set created_at = ? where reference = ?", Timestamp.from(Instant.now().minus(age)), reference));
    }

    private long rowsFor(String email) {
        return jdbc.queryForObject("select count(*) from payment where email = ?", Long.class, email);
    }

    private long rowsFor(String email, long amountMinor) {
        return jdbc.queryForObject("select count(*) from payment where email = ? and amount_minor = ?", Long.class, email, amountMinor);
    }

    private List<PaymentStatus> historyOf(String reference) {
        var payment = payments.findByReference(reference).orElseThrow();
        return transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()).stream().map(PaymentTransition::getToStatus).toList();
    }

    private ResultActions initiate(String ownerId, String json) throws Exception {
        return mvc.perform(post(PAYMENTS)
                .with(authentication(asClient(ownerId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private List<Object> runConcurrently(int count, IntFunction<InitiatePaymentCommand> commands) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Object>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                var command = commands.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return initiation.initiate(command);
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static UsernamePasswordAuthenticationToken asClient(String ownerId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ApiClient(ownerId, Role.CLIENT), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    private static String body(String reference, String email, String amount) {
        return """
                {"reference":"%s","email":"%s","amount":%s,"currency":"GHS"}
                """.formatted(reference, email, amount);
    }

    private static InitiatePaymentCommand command(String ownerId, String reference, String email, String amount) {
        return InitiatePaymentCommand.builder()
                .ownerId(ownerId)
                .reference(reference)
                .email(email)
                .amount(new BigDecimal(amount))
                .currency("GHS")
                .build();
    }

    private static String uniqueReference() {
        return "PAY-" + UUID.randomUUID();
    }

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@example.com";
    }
}
