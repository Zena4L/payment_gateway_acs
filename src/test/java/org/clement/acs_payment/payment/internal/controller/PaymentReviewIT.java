package org.clement.acs_payment.payment.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
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
import org.clement.acs_payment.payment.internal.service.ProviderOutcomeWriter;
import org.clement.acs_payment.paystack.ProviderStatus;
import org.clement.acs_payment.paystack.ProviderTransaction;
import org.clement.acs_payment.paystack.VerificationResult;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PaymentReviewIT {

    private static final String ADMIN = "ops-admin";
    private static final String REASON = "Customer bank confirmed the transfer of GHS 150.00";

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    ProviderOutcomeWriter outcomes;

    @Autowired
    Clock clock;

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "FAILED"})
    void resolvesPaymentUnderReviewAndRecordsWhoAndWhy(PaymentStatus outcome) throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);

        resolve(payment.getId().toString(), outcome.name(), REASON, Role.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.status").value(outcome.name()));

        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(outcome);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).last()
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy,
                        PaymentTransition::getActor, PaymentTransition::getReason)
                .containsExactly(PaymentStatus.REQUIRES_REVIEW, outcome, TransitionTrigger.ADMIN, ADMIN, REASON);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "SUCCESS"})
    void refusesToResolvePaymentNotUnderReview(PaymentStatus current) throws Exception {
        var payment = paymentIn(current);

        resolve(payment.getId().toString(), "SUCCESS", REASON, Role.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid-transition"));

        var saved = payments.findById(payment.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(current);
        assertThat(saved.getVersion()).isEqualTo(payment.getVersion());
    }

    @Test
    void returnsNotFoundForUnknownPayment() throws Exception {
        resolve(UUID.randomUUID().toString(), "SUCCESS", REASON, Role.ADMIN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment-not-found"));
    }

    @Test
    void rejectsMalformedPaymentId() throws Exception {
        resolve("not-a-uuid", "SUCCESS", REASON, Role.ADMIN).andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "outcome PENDING  | {\"outcome\":\"PENDING\",\"reason\":\"checked\"}",
            "missing outcome  | {\"reason\":\"checked\"}",
            "blank reason     | {\"outcome\":\"SUCCESS\",\"reason\":\"  \"}",
            "missing reason   | {\"outcome\":\"SUCCESS\"}"
    })
    void rejectsInvalidResolutionRequest(String scenario, String body) throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);

        mvc.perform(post("/api/v1/admin/payments/{id}/resolution", payment.getId())
                        .with(authentication(principal(ADMIN, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
    }

    @Test
    void rejectsReasonLongerThan255Characters() throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);

        resolve(payment.getId().toString(), "SUCCESS", "x".repeat(256), Role.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void forbidsClientKeys() throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);

        resolve(payment.getId().toString(), "SUCCESS", REASON, Role.CLIENT).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/payments/{id}/transitions", payment.getId())
                        .with(authentication(principal("client-a", Role.CLIENT))))
                .andExpect(status().isForbidden());

        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/admin/payments/{id}/resolution", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCESS\",\"reason\":\"checked\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/payments/{id}/transitions", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void letsOnlyOneOfTwoConcurrentResolutionsWin() throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);
        var start = new CountDownLatch(1);
        var statuses = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var outcome : List.of("SUCCESS", "FAILED")) {
                statuses.add(executor.submit(() -> {
                    start.await();
                    return resolve(payment.getId().toString(), outcome, REASON, Role.ADMIN).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            var codes = new ArrayList<Integer>();
            for (var future : statuses) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        }

        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .filteredOn(transition -> transition.getTriggeredBy() == TransitionTrigger.ADMIN)
                .hasSize(1);
    }

    @Test
    void showsTheFullAuditTrail() throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);
        resolve(payment.getId().toString(), "SUCCESS", REASON, Role.ADMIN).andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/payments/{id}/transitions", payment.getId())
                        .with(authentication(principal(ADMIN, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].from").doesNotExist())
                .andExpect(jsonPath("$[0].to").value("INITIALIZING"))
                .andExpect(jsonPath("$[0].actor").doesNotExist())
                .andExpect(jsonPath("$[2].to").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$[2].trigger").value("VERIFY"))
                .andExpect(jsonPath("$[3].from").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$[3].to").value("SUCCESS"))
                .andExpect(jsonPath("$[3].trigger").value("ADMIN"))
                .andExpect(jsonPath("$[3].actor").value(ADMIN))
                .andExpect(jsonPath("$[3].reason").value(REASON));
    }

    @Test
    void returnsNotFoundForAuditTrailOfUnknownPayment() throws Exception {
        mvc.perform(get("/api/v1/admin/payments/{id}/transitions", UUID.randomUUID())
                        .with(authentication(principal(ADMIN, Role.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsManualResolutionAgainstLaterProviderResults() throws Exception {
        var payment = paymentIn(PaymentStatus.REQUIRES_REVIEW);
        resolve(payment.getId().toString(), "SUCCESS", REASON, Role.ADMIN).andExpect(status().isOk());

        var applied = outcomes.apply(payment.getId(), new VerificationResult.Found(ProviderTransaction.builder()
                .id(1L)
                .status(ProviderStatus.FAILED)
                .rawStatus("failed")
                .reference(payment.getReference())
                .amountMinor(payment.getAmountMinor())
                .currency("GHS")
                .build()), TransitionTrigger.WEBHOOK);

        assertThat(applied.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .extracting(PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .last()
                .isEqualTo(tuple(PaymentStatus.SUCCESS, TransitionTrigger.ADMIN));
    }

    private ResultActions resolve(String id, String outcome, String reason, Role role) throws Exception {
        return mvc.perform(post("/api/v1/admin/payments/{id}/resolution", id)
                .with(authentication(principal(role == Role.ADMIN ? ADMIN : "client-a", role)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"%s\",\"reason\":\"%s\"}".formatted(outcome, reason)));
    }

    private static UsernamePasswordAuthenticationToken principal(String ownerId, Role role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ApiClient(ownerId, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    private Payment paymentIn(PaymentStatus status) {
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        var payment = Payment.initialize("PAY-" + UUID.randomUUID(), "client-a", "jane@example.com", money, clock);
        payment.markInitialized("https://checkout.paystack.com/abc", clock);
        switch (status) {
            case PENDING -> {
            }
            case REQUIRES_REVIEW -> payment.flagForReview(TransitionTrigger.VERIFY, clock);
            case SUCCESS -> payment.transitionTo(PaymentStatus.SUCCESS, TransitionTrigger.VERIFY, clock);
            default -> throw new IllegalArgumentException("Unsupported seed status " + status);
        }
        return payments.save(payment);
    }
}
