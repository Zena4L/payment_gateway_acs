package org.clement.acs_payment.payment.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.payment.internal.repository.PaymentTransitionRepository;
import org.clement.acs_payment.payment.internal.service.PaymentVerificationService;
import org.clement.acs_payment.payment.internal.service.ProviderOutcomeWriter;
import org.clement.acs_payment.paystack.PaystackGateway;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentVerificationIT {

    private static final Instant PAID_AT = Instant.parse("2026-11-06T10:15:30Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    PaymentVerificationService verification;

    @Autowired
    ProviderOutcomeWriter outcomes;

    @Autowired
    Clock clock;

    @MockitoBean
    PaystackGateway paystack;

    @Test
    void returnsNotFoundForUnknownReferenceWithoutCallingPaystack() throws Exception {
        verifyAs("client-a", Role.CLIENT, "PAY-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment-not-found"));

        verify(paystack, never()).verify(anyString());
    }

    @Test
    void hidesOtherClientsPaymentsWithoutCallingPaystack() throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");

        verifyAs("client-b", Role.CLIENT, payment.getReference())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment-not-found"));

        verify(paystack, never()).verify(anyString());
    }

    @Test
    void letsAdminVerifyAnyPayment() throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        providerReports(payment, ProviderStatus.SUCCESS);

        verifyAs("bootstrap-admin", Role.ADMIN, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void confirmsSuccessfulPaymentAndRecordsProviderDetails() throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        var updatedBefore = payment.getUpdatedAt();
        providerReports(payment, ProviderStatus.SUCCESS);

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(payment.getReference()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.channel").value("card"))
                .andExpect(jsonPath("$.paidAt").value("2026-11-06T10:15:30Z"));

        var saved = payments.findById(payment.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(saved.getProviderTransactionId()).isEqualTo(4099260516L);
        assertThat(saved.getChannel()).isEqualTo("card");
        assertThat(saved.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(saved.getGatewayResponse()).isEqualTo("Approved");
        assertThat(saved.getUpdatedAt()).isAfter(updatedBefore);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).last()
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .containsExactly(PaymentStatus.PENDING, PaymentStatus.SUCCESS, TransitionTrigger.VERIFY);
    }

    @ParameterizedTest
    @CsvSource({"FAILED, FAILED", "PENDING, PENDING", "REVERSED, REQUIRES_REVIEW"})
    void mapsProviderStatusToLocalStatus(ProviderStatus providerStatus, PaymentStatus expected) throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        providerReports(payment, providerStatus);

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected.name()));

        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(expected);
    }

    @Test
    void storesProviderDetailsOnFailure() throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        providerReports(payment, ProviderStatus.FAILED);

        verifyAs("client-a", Role.CLIENT, payment.getReference()).andExpect(status().isOk());

        var saved = payments.findById(payment.getId()).orElseThrow();
        assertThat(saved.getProviderTransactionId()).isEqualTo(4099260516L);
        assertThat(saved.getGatewayResponse()).isEqualTo("Approved");
    }

    @Test
    void leavesPaymentUnchangedForUnrecognisedProviderStatus(CapturedOutput output) throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        when(paystack.verify(payment.getReference())).thenReturn(found(transaction(payment, ProviderStatus.UNRECOGNIZED, "brand-new-status")));

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(output.getAll()).contains("unrecognised provider status 'brand-new-status'");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"amount", "currency", "reference"})
    void flagsMismatchedProviderDataForReview(String field, CapturedOutput output) throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        var mismatched = ProviderTransaction.builder()
                .id(4099260516L)
                .status(ProviderStatus.SUCCESS)
                .rawStatus("success")
                .reference(field.equals("reference") ? "SOMETHING-ELSE" : payment.getReference())
                .amountMinor(field.equals("amount") ? 1500 : 15000)
                .currency(field.equals("currency") ? "NGN" : "GHS")
                .build();
        when(paystack.verify(payment.getReference())).thenReturn(found(mismatched));

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"));

        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
        assertThat(output.getAll())
                .contains("Provider data mismatch for reference " + payment.getReference())
                .contains("expected reference=" + payment.getReference() + " amountMinor=15000 currency=GHS")
                .doesNotContain("jane@example.com");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "FAILED", "EXPIRED", "REQUIRES_REVIEW"})
    void returnsStoredResultForSettledPaymentsWithoutCallingPaystack(PaymentStatus settled) throws Exception {
        var payment = paymentIn(settled, "client-a");

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(settled.name()));
        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(settled.name()));

        verify(paystack, never()).verify(anyString());
        assertThat(payments.findById(payment.getId()).orElseThrow().getVersion()).isEqualTo(payment.getVersion());
    }

    @Test
    void ignoresProviderResultThatContradictsFinalState(CapturedOutput output) {
        var payment = paymentIn(PaymentStatus.SUCCESS, "client-a");

        var applied = outcomes.apply(payment.getId(), found(transaction(payment, ProviderStatus.FAILED, "failed")), TransitionTrigger.VERIFY);

        assertThat(applied.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(output.getAll()).contains("Rejected payment transition SUCCESS -> FAILED for reference " + payment.getReference());
    }

    @Test
    void appliesConcurrentVerificationsExactlyOnce() throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        providerReports(payment, ProviderStatus.SUCCESS);
        var caller = new ApiClient("client-a", Role.CLIENT);

        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Payment>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return verification.verify(payment.getReference(), caller);
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            }
        }

        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .filteredOn(transition -> transition.getToStatus() == PaymentStatus.SUCCESS)
                .hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"PENDING, PENDING", "SUCCESS, SUCCESS", "FAILED, FAILED"})
    void resolvesUnknownPaymentFromProviderStatus(ProviderStatus providerStatus, PaymentStatus expected) throws Exception {
        var payment = paymentIn(PaymentStatus.UNKNOWN, "client-a");
        providerReports(payment, providerStatus);

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected.name()));
    }

    @Test
    void failsUnknownPaymentThatPaystackNeverCreated() throws Exception {
        var payment = paymentIn(PaymentStatus.UNKNOWN, "client-a");
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.NotFound());

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(payments.findById(payment.getId()).orElseThrow().getGatewayResponse()).isEqualTo("Transaction not found at provider");
    }

    @Test
    void leavesInitializingPaymentAloneWhenPaystackHasNoRecordYet() throws Exception {
        var payment = paymentIn(PaymentStatus.INITIALIZING, "client-a");
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.NotFound());

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INITIALIZING"));
    }

    @Test
    void reportsUnavailableProviderAndLeavesPaymentUnchanged() throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.Unavailable());

        verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("provider-unavailable"));

        var saved = payments.findById(payment.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getVersion()).isEqualTo(payment.getVersion());
    }

    @Test
    void reportsProviderRejectionAndLeavesPaymentUnchanged(CapturedOutput output) throws Exception {
        var payment = paymentIn(PaymentStatus.PENDING, "client-a");
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.Rejected("Invalid key"));

        var body = verifyAs("client-a", Role.CLIENT, payment.getReference())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("provider-rejected"))
                .andReturn().getResponse().getContentAsString();

        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(body).doesNotContain("Invalid key").doesNotContain("Exception");
        assertThat(output.getAll()).doesNotContain("sk_test_dummy");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/payments/verify/{reference}", "PAY-1")).andExpect(status().isUnauthorized());

        verify(paystack, never()).verify(any());
    }

    private ResultActions verifyAs(String ownerId, Role role, String reference) throws Exception {
        var principal = UsernamePasswordAuthenticationToken.authenticated(
                new ApiClient(ownerId, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        return mvc.perform(get("/api/v1/payments/verify/{reference}", reference).with(authentication(principal)));
    }

    private void providerReports(Payment payment, ProviderStatus status) {
        when(paystack.verify(payment.getReference())).thenReturn(found(transaction(payment, status, status.name().toLowerCase())));
    }

    private static VerificationResult found(ProviderTransaction transaction) {
        return new VerificationResult.Found(transaction);
    }

    private static ProviderTransaction transaction(Payment payment, ProviderStatus status, String rawStatus) {
        return ProviderTransaction.builder()
                .id(4099260516L)
                .status(status)
                .rawStatus(rawStatus)
                .reference(payment.getReference())
                .amountMinor(payment.getAmountMinor())
                .currency(payment.getCurrency().getCurrencyCode())
                .channel("card")
                .paidAt(PAID_AT)
                .gatewayResponse("Approved")
                .build();
    }

    private Payment paymentIn(PaymentStatus status, String ownerId) {
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        var payment = Payment.initialize("PAY-" + UUID.randomUUID(), ownerId, "jane@example.com", money, clock);
        switch (status) {
            case INITIALIZING -> {
            }
            case PENDING -> payment.markInitialized("https://checkout.paystack.com/abc", clock);
            case UNKNOWN -> payment.markOutcomeUnknown(clock);
            case FAILED -> payment.markFailed("Declined", TransitionTrigger.INITIATE, clock);
            case SUCCESS -> {
                payment.markInitialized("https://checkout.paystack.com/abc", clock);
                payment.transitionTo(PaymentStatus.SUCCESS, TransitionTrigger.VERIFY, clock);
            }
            case EXPIRED -> {
                payment.markInitialized("https://checkout.paystack.com/abc", clock);
                payment.transitionTo(PaymentStatus.EXPIRED, TransitionTrigger.RECONCILER, clock);
            }
            case REQUIRES_REVIEW -> {
                payment.markInitialized("https://checkout.paystack.com/abc", clock);
                payment.flagForReview(TransitionTrigger.VERIFY, clock);
            }
        }
        return payments.save(payment);
    }
}
