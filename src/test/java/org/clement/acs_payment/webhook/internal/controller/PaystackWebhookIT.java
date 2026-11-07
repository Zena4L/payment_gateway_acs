package org.clement.acs_payment.webhook.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.TransitionCause;
import org.clement.acs_payment.payment.internal.model.TestActors;
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
import org.clement.acs_payment.webhook.internal.model.ProcessingState;
import org.clement.acs_payment.webhook.internal.model.WebhookEvent;
import org.clement.acs_payment.webhook.internal.repository.WebhookEventRepository;
import org.clement.acs_payment.webhook.internal.service.WebhookEventWriter;
import org.clement.acs_payment.webhook.internal.service.WebhookIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PaystackWebhookIT {

    private static final String WEBHOOK = "/api/v1/webhooks/paystack";
    private static final String SECRET = "sk_test_dummy";
    private static final Duration PROCESSING = Duration.ofSeconds(10);
    private static final AtomicLong TRANSACTION_IDS = new AtomicLong(System.nanoTime());

    @Autowired
    MockMvc mvc;

    @Autowired
    WebhookEventRepository webhookEvents;

    @Autowired
    WebhookIngestionService ingestion;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentTransitionRepository transitions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @MockitoBean
    PaystackGateway paystack;

    @MockitoSpyBean
    WebhookEventWriter writer;

    @Test
    void acceptsSignedEventWithoutApiKey() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        providerConfirms(payment, ProviderStatus.SUCCESS);

        deliver(body, sign(body)).andExpect(status().isOk());

        awaitState(body, ProcessingState.PROCESSED);
    }

    @Test
    void rejectsMissingSignatureWithoutStoringAnything() throws Exception {
        var body = chargeSuccess("PAY-" + UUID.randomUUID(), 15000, "GHS");
        var before = webhookEvents.count();

        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(webhookEvents.count()).isEqualTo(before);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void rejectsWrongSignatureWithoutStoringAnything() throws Exception {
        var body = chargeSuccess("PAY-" + UUID.randomUUID(), 15000, "GHS");
        var before = webhookEvents.count();

        deliver(body, "0".repeat(128)).andExpect(status().isUnauthorized());

        assertThat(webhookEvents.count()).isEqualTo(before);
    }

    @Test
    void rejectsBodyChangedAfterSigning() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        var signature = sign(body);
        var before = webhookEvents.count();

        deliver(body.replace("15000", "1500000"), signature).andExpect(status().isUnauthorized());

        assertThat(webhookEvents.count()).isEqualTo(before);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void storesEventAndRecordsProcessingResult() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        var signature = sign(body);
        providerConfirms(payment, ProviderStatus.SUCCESS);

        deliver(body, signature).andExpect(status().isOk());

        var event = awaitState(body, ProcessingState.PROCESSED);
        assertThat(event.getDedupKey()).isEqualTo("charge.success:" + transactionId(body));
        assertThat(event.getEventType()).isEqualTo("charge.success");
        assertThat(event.getReference()).isEqualTo(payment.getReference());
        assertThat(event.getReceivedAt()).isNotNull();
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getDiagnostic()).isEqualTo("Payment is SUCCESS (was PENDING)");
        assertThat(jdbc.queryForObject("select count(*) from webhook_event where position(? in coalesce(diagnostic, '') || coalesce(dedup_key, '')) > 0",
                Long.class, signature)).isZero();
    }

    @Test
    void confirmsWithPaystackBeforeMarkingPaymentSuccessful() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        providerConfirms(payment, ProviderStatus.SUCCESS);

        deliver(body, sign(body)).andExpect(status().isOk());

        awaitState(body, ProcessingState.PROCESSED);
        verify(paystack, times(1)).verify(payment.getReference());
        var saved = payments.findById(payment.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(saved.getProviderTransactionId()).isEqualTo(4099260516L);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).last()
                .extracting(PaymentTransition::getFromStatus, PaymentTransition::getToStatus, PaymentTransition::getTriggeredBy)
                .containsExactly(PaymentStatus.PENDING, PaymentStatus.SUCCESS, TransitionTrigger.WEBHOOK);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderStatus.class, names = {"FAILED", "PENDING"})
    void trustsVerificationOverWebhookPayload(ProviderStatus verified) throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        providerConfirms(payment, verified);

        deliver(body, sign(body)).andExpect(status().isOk());

        awaitState(body, ProcessingState.PROCESSED);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(verified == ProviderStatus.FAILED ? PaymentStatus.FAILED : PaymentStatus.PENDING);
    }

    @Test
    void flagsAmountMismatchForReviewWithoutCallingPaystack() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 1500, "GHS");

        deliver(body, sign(body)).andExpect(status().isOk());

        var event = awaitState(body, ProcessingState.PROCESSED);
        assertThat(event.getDiagnostic()).contains("REQUIRES_REVIEW");
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void flagsCurrencyMismatchForReview() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "NGN");

        deliver(body, sign(body)).andExpect(status().isOk());

        awaitState(body, ProcessingState.PROCESSED);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
    }

    @Test
    void acknowledgesDuplicateDeliveryWithoutReapplying() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        providerConfirms(payment, ProviderStatus.SUCCESS);

        deliver(body, sign(body)).andExpect(status().isOk());
        awaitState(body, ProcessingState.PROCESSED);
        deliver(body, sign(body)).andExpect(status().isOk());

        assertThat(webhookEvents.findByDedupKeyOrderByReceivedAtAsc(dedupKey(body)))
                .extracting(WebhookEvent::getProcessingState)
                .containsExactlyInAnyOrder(ProcessingState.PROCESSED, ProcessingState.DUPLICATE);
        verify(paystack, times(1)).verify(anyString());
        assertSingleSuccessTransition(payment);
    }

    @Test
    void appliesConcurrentIdenticalDeliveriesOnce() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var signature = sign(body);
        providerConfirms(payment, ProviderStatus.SUCCESS);

        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    ingestion.receive(bytes, signature);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        awaitState(body, ProcessingState.PROCESSED);
        assertThat(webhookEvents.findByDedupKeyOrderByReceivedAtAsc(dedupKey(body)))
                .extracting(WebhookEvent::getProcessingState)
                .containsOnlyOnce(ProcessingState.PROCESSED)
                .filteredOn(state -> state == ProcessingState.DUPLICATE).hasSize(9);
        verify(paystack, times(1)).verify(anyString());
        assertSingleSuccessTransition(payment);
    }

    @Test
    void recordsButDoesNotApplyEventForFailedPayment() throws Exception {
        var payment = paymentInFinalState(PaymentStatus.FAILED);
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");

        deliver(body, sign(body)).andExpect(status().isOk());

        var event = awaitState(body, ProcessingState.REJECTED);
        assertThat(event.getDiagnostic()).isEqualTo("Payment is FAILED; event not applied");
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void treatsEventForAlreadySuccessfulPaymentAsProcessed() throws Exception {
        var payment = paymentInFinalState(PaymentStatus.SUCCESS);
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        var transitionsBefore = transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()).size();

        deliver(body, sign(body)).andExpect(status().isOk());

        awaitState(body, ProcessingState.PROCESSED);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId())).hasSize(transitionsBefore);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void ignoresUnhandledEventTypes() throws Exception {
        var body = """
                {"event":"transfer.success","data":{"id":%d,"reference":"TRF-1","amount":100,"currency":"GHS"}}
                """.formatted(TRANSACTION_IDS.incrementAndGet());
        var before = webhookEvents.count();

        deliver(body, sign(body)).andExpect(status().isOk());

        assertThat(webhookEvents.count()).isEqualTo(before + 1);
        assertThat(webhookEvents.findAll()).filteredOn(event -> event.getEventType().equals("transfer.success"))
                .extracting(WebhookEvent::getProcessingState).contains(ProcessingState.IGNORED);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void ignoresEventForUnknownReference(CapturedOutput output) throws Exception {
        var reference = "PAY-" + UUID.randomUUID();
        var body = chargeSuccess(reference, 15000, "GHS");

        deliver(body, sign(body)).andExpect(status().isOk());

        var event = awaitState(body, ProcessingState.IGNORED);
        assertThat(event.getDiagnostic()).isEqualTo("No payment with this reference");
        assertThat(output.getAll()).contains("refers to unknown reference " + reference);
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void rejectsSignedButMalformedPayload() throws Exception {
        var body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"PAY-X\"}}";
        var before = webhookEvents.count();

        deliver(body, sign(body)).andExpect(status().isOk());

        assertThat(webhookEvents.count()).isEqualTo(before + 1);
        assertThat(webhookEvents.findAll()).extracting(WebhookEvent::getProcessingState).contains(ProcessingState.REJECTED);
    }

    @Test
    void returnsServerErrorWhenEventCannotBeStored() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        doThrow(new DataAccessResourceFailureException("database down")).when(writer).recordReceived(any());

        deliver(body, sign(body)).andExpect(status().is5xxServerError());

        assertThat(webhookEvents.findByDedupKeyOrderByReceivedAtAsc(dedupKey(body))).isEmpty();
        verify(paystack, never()).verify(anyString());
    }

    @Test
    void acknowledgesBeforeSlowProcessingFinishes() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        when(paystack.verify(payment.getReference())).thenAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(2);
            return found(payment, ProviderStatus.SUCCESS);
        });

        var started = System.nanoTime();
        deliver(body, sign(body)).andExpect(status().isOk());
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        awaitState(body, ProcessingState.PROCESSED);
    }

    @Test
    void recordsFailureWhenPaystackCannotConfirm() throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        when(paystack.verify(payment.getReference())).thenReturn(new VerificationResult.Unavailable());

        deliver(body, sign(body)).andExpect(status().isOk());

        var event = awaitState(body, ProcessingState.FAILED);
        assertThat(event.getDiagnostic()).isEqualTo("Could not confirm with provider; payment left unchanged");
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void neverLogsSignatureOrSecret(CapturedOutput output) throws Exception {
        var payment = pendingPayment();
        var body = chargeSuccess(payment.getReference(), 15000, "GHS");
        var signature = sign(body);
        providerConfirms(payment, ProviderStatus.SUCCESS);

        deliver(body, signature).andExpect(status().isOk());
        deliver(body, "f".repeat(128)).andExpect(status().isUnauthorized());
        awaitState(body, ProcessingState.PROCESSED);

        assertThat(output.getAll()).doesNotContain(signature).doesNotContain(SECRET).doesNotContain("jane@example.com");
    }

    private ResultActions deliver(String body, String signature) throws Exception {
        return mvc.perform(post(WEBHOOK)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PaystackWebhookController.SIGNATURE_HEADER, signature)
                .content(body));
    }

    private WebhookEvent awaitState(String body, ProcessingState expected) {
        var key = dedupKey(body);
        return await().atMost(PROCESSING).until(
                () -> webhookEvents.findByDedupKeyOrderByReceivedAtAsc(key).stream()
                        .filter(event -> event.getProcessingState() != ProcessingState.DUPLICATE)
                        .findFirst()
                        .filter(event -> event.getProcessingState() == expected)
                        .orElse(null),
                event -> event != null);
    }

    private void assertSingleSuccessTransition(Payment payment) {
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .filteredOn(transition -> transition.getToStatus() == PaymentStatus.SUCCESS)
                .hasSize(1);
    }

    private void providerConfirms(Payment payment, ProviderStatus status) {
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
                .paidAt(Instant.parse("2026-11-06T10:15:30Z"))
                .gatewayResponse("Approved")
                .build());
    }

    private Payment pendingPayment() {
        var payment = newPayment();
        payment.markInitialized("https://checkout.paystack.com/abc", TestActors.initiatorOf(payment), clock);
        return payments.save(payment);
    }

    private Payment paymentInFinalState(PaymentStatus status) {
        var payment = newPayment();
        if (status == PaymentStatus.FAILED) {
            payment.markFailed("Declined", TransitionCause.system(TransitionTrigger.INITIATE), clock);
        } else {
            payment.markInitialized("https://checkout.paystack.com/abc", TestActors.initiatorOf(payment), clock);
            payment.transitionTo(status, TransitionCause.system(TransitionTrigger.VERIFY), clock);
        }
        return payments.save(payment);
    }

    private Payment newPayment() {
        var money = new Money(new BigDecimal("150.00"), Currency.getInstance("GHS"));
        return Payment.initialize("PAY-" + UUID.randomUUID(), TestActors.of("client-a"), "jane@example.com", money, clock);
    }

    private static String chargeSuccess(String reference, long amountMinor, String currency) {
        return """
                {"event":"charge.success","data":{"id":%d,"status":"success","reference":"%s","amount":%d,"currency":"%s",
                 "customer":{"email":"jane@example.com"}}}
                """.formatted(TRANSACTION_IDS.incrementAndGet(), reference, amountMinor, currency);
    }

    private static long transactionId(String body) {
        var start = body.indexOf("\"id\":") + 5;
        return Long.parseLong(body.substring(start, body.indexOf(',', start)));
    }

    private static String dedupKey(String body) {
        return "charge.success:" + transactionId(body);
    }

    private static String sign(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
