package org.clement.acs_payment.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.SandboxKey;
import org.clement.acs_payment.payment.PaymentReconciliation;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.PaymentTransition;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.payment.internal.repository.PaymentTransitionRepository;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.clement.acs_payment.webhook.internal.model.ProcessingState;
import org.clement.acs_payment.webhook.internal.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class PaymentLifecycleSandboxE2E {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    WebhookEventRepository webhookEvents;

    @Autowired
    PaymentReconciliation reconciliation;

    @BeforeAll
    static void requireSandboxKey() {
        SandboxKey.secretKey();
    }

    @DynamicPropertySource
    static void useRealPaystack(DynamicPropertyRegistry registry) {
        registry.add("acs.paystack.base-url", () -> SandboxKey.PAYSTACK_BASE_URL);
        registry.add("acs.paystack.secret-key", SandboxKey::secretKey);
    }

    @Test
    void runsThePaymentLifecycleAgainstPaystack() throws Exception {
        var reference = "E2E-" + UUID.randomUUID();
        var request = """
                {"reference":"%s","email":"e2e@example.com","amount":150.00,"currency":"GHS"}
                """.formatted(reference);

        var created = mvc.perform(post("/api/v1/payments/initiate").with(asClient())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String checkoutUrl = JsonPath.read(created, "$.authorizationUrl");
        assertThat(checkoutUrl).startsWith("https://checkout.paystack.com/");
        var payment = payments.findByReference(reference).orElseThrow();
        assertThat(historyOf(payment.getId())).containsExactly(PaymentStatus.INITIALIZING, PaymentStatus.PENDING);

        mvc.perform(post("/api/v1/payments/initiate").with(asClient())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value(checkoutUrl));

        mvc.perform(get("/api/v1/payments/verify/{reference}", reference).with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        var transactionId = System.currentTimeMillis();
        var forged = """
                {"event":"charge.success","data":{"id":%d,"status":"success","reference":"%s","amount":15000,"currency":"GHS"}}
                """.formatted(transactionId, reference);
        mvc.perform(post("/api/v1/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", SandboxKey.sign(forged))
                        .content(forged))
                .andExpect(status().isOk());
        var processed = await().atMost(Duration.ofSeconds(30)).until(
                () -> webhookEvents.findByDedupKeyOrderByReceivedAtAsc("charge.success:" + transactionId).stream()
                        .filter(event -> event.getProcessingState() == ProcessingState.PROCESSED)
                        .findFirst().orElse(null),
                event -> event != null);
        assertThat(processed.getDiagnostic()).isEqualTo("Payment is PENDING (was PENDING)");
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);

        var report = reconciliation.reconcile(Duration.ZERO, Duration.ofMillis(1), 10);

        assertThat(report.expired()).isGreaterThanOrEqualTo(1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).last()
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .containsExactly(PaymentStatus.PENDING, PaymentStatus.EXPIRED, TransitionTrigger.RECONCILER);
    }

    private List<PaymentStatus> historyOf(UUID paymentId) {
        return transitions.findByPaymentIdOrderByOccurredAtAsc(paymentId).stream().map(PaymentTransition::getToStatus).toList();
    }

    private static RequestPostProcessor asClient() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new ApiClient("e2e-client", Role.CLIENT), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));
    }
}
