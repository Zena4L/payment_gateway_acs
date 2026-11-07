package org.clement.acs_payment.payment.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.TestActors;
import org.clement.acs_payment.payment.internal.dto.InitiatePaymentRequest;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentInitiationIT {

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

    @MockitoBean
    PaystackGateway paystack;

    @Test
    void savesPaymentAsInitializingBeforeCallingPaystack() throws Exception {
        var reference = uniqueReference();
        var statusSeenByPaystack = new AtomicReference<String>();
        when(paystack.initialize(any())).thenAnswer(invocation -> {
            statusSeenByPaystack.set(jdbc.queryForObject("select status from payment where reference = ?", String.class, reference));
            return INITIALIZED;
        });

        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS")).andExpect(status().isCreated());

        assertThat(statusSeenByPaystack.get()).isEqualTo("INITIALIZING");
    }

    @Test
    void initiatesPaymentAndReturnsCheckoutLink() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);

        var body = initiate("client-a", body(reference, "Jane@Example.com", "150.00", "GHS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.authorizationUrl").value(CHECKOUT_URL))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.currency").value("GHS"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.accessCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Boolean>read(body, "$.success")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.message")).isEqualTo("Payment initialized successfully").doesNotContainIgnoringCase("paid");
        assertThat(JsonPath.<Object>read(body, "$.paidAt")).isNull();
        var payment = payments.findByReference(reference).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAuthorizationUrl()).isEqualTo(CHECKOUT_URL);
        assertThat(payment.getOwnerId()).isEqualTo("client-a");
        assertThat(payment.getAmountMinor()).isEqualTo(15000L);
        assertThat(transitions.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .extracting(PaymentTransition::getToStatus, PaymentTransition::getActor, PaymentTransition::getActorKeyId)
                .containsExactly(
                        tuple(PaymentStatus.INITIALIZING, "client-a", TestActors.KEY_ID),
                        tuple(PaymentStatus.PENDING, "client-a", TestActors.KEY_ID));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing email          | ''                | 150.00  | GHS | REF   | email",
            "malformed email        | not-an-email      | 150.00  | GHS | REF   | email",
            "zero amount            | jane@example.com  | 0       | GHS | REF   | amount",
            "negative amount        | jane@example.com  | -5.00   | GHS | REF   | amount",
            "missing amount         | jane@example.com  |         | GHS | REF   | amount",
            "three decimal places   | jane@example.com  | 150.005 | GHS | REF   | amount",
            "too many whole digits  | jane@example.com  | 99999999999999999.99 | GHS | REF | amount",
            "malformed currency     | jane@example.com  | 150.00  | gh  | REF   | currency",
            "missing reference      | jane@example.com  | 150.00  | GHS | ''    | reference",
            "reference too long     | jane@example.com  | 150.00  | GHS | LONG  | reference",
            "reference bad chars    | jane@example.com  | 150.00  | GHS | a b/c | reference"
    })
    void rejectsInvalidRequestWithoutSavingOrCallingPaystack(String scenario, String email, String amount, String currency,
                                                            String reference, String field) throws Exception {
        var actualReference = switch (reference) {
            case "REF" -> uniqueReference();
            case "LONG" -> "R".repeat(65);
            default -> reference;
        };
        var before = payments.count();

        initiate("client-a", body(actualReference, email, amount, currency))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value(field));

        assertThat(payments.count()).isEqualTo(before);
        verify(paystack, never()).initialize(any());
    }

    @Test
    void explainsWhichCharactersAReferenceMayContain() throws Exception {
        initiate("client-a", body("test demo", "jane@example.com", "150.00", "GHS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("reference"))
                .andExpect(jsonPath("$.errors[0].message").value(InitiatePaymentRequest.REFERENCE_CHARACTERS));
    }

    @Test
    void rejectsUnsupportedCurrencyWithoutSavingOrCallingPaystack() throws Exception {
        var reference = uniqueReference();

        initiate("client-a", body(reference, "jane@example.com", "150.00", "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported-currency"));

        assertThat(payments.findByReference(reference)).isEmpty();
        verify(paystack, never()).initialize(any());
    }

    @Test
    void replaysIdenticalRequestWithoutCallingPaystackAgain() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS")).andExpect(status().isCreated());

        initiate("client-a", body(reference, "jane@example.com", "150", "GHS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.authorizationUrl").value(CHECKOUT_URL));

        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void allowsSameDetailsUnderNewReferenceWhenDuplicateWindowIsZero() throws Exception {
        var email = "guard-off-" + UUID.randomUUID() + "@example.com";
        when(paystack.initialize(any())).thenReturn(INITIALIZED);

        initiate("client-a", body(uniqueReference(), email, "150.00", "GHS")).andExpect(status().isCreated());
        initiate("client-a", body(uniqueReference(), email, "150.00", "GHS")).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from payment where email = ?", Long.class, email)).isEqualTo(2L);
        verify(paystack, times(2)).initialize(any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "different amount   | client-a | jane@example.com | 200.00",
            "different email    | client-a | john@example.com | 150.00",
            "different owner    | client-b | jane@example.com | 150.00"
    })
    void rejectsConflictingReuseOfReference(String scenario, String owner, String email, String amount) throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);
        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS")).andExpect(status().isCreated());
        var original = payments.findByReference(reference).orElseThrow();

        initiate(owner, body(reference, email, amount, "GHS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payment-conflict"));

        var after = payments.findByReference(reference).orElseThrow();
        assertThat(after.getAmount()).isEqualByComparingTo(original.getAmount());
        assertThat(after.getEmail()).isEqualTo(original.getEmail());
        assertThat(after.getVersion()).isEqualTo(original.getVersion());
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void createsOnePaymentForConcurrentIdenticalRequests() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(300);
            return INITIALIZED;
        });

        var results = runConcurrently(10, i -> command(reference, "150.00"));

        assertThat(jdbc.queryForObject("select count(*) from payment where reference = ?", Long.class, reference)).isEqualTo(1L);
        verify(paystack, times(1)).initialize(any());
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PaymentInitiation.class));
        assertThat(results).filteredOn(result -> result instanceof PaymentInitiation initiation && !initiation.replayed()).hasSize(1);
        assertThat(results).filteredOn(result -> result instanceof PaymentInitiation initiation && initiation.replayed())
                .allSatisfy(result -> assertThat(((PaymentInitiation) result).payment().getStatus())
                        .isIn(PaymentStatus.INITIALIZING, PaymentStatus.PENDING));
    }

    @Test
    void rejectsConcurrentConflictingRequestsWithoutServerErrors() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);

        var results = runConcurrently(10, i -> command(reference, (100 + i) + ".00"));

        assertThat(jdbc.queryForObject("select count(*) from payment where reference = ?", Long.class, reference)).isEqualTo(1L);
        assertThat(results).filteredOn(PaymentInitiation.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(PaymentConflictException.class::isInstance).hasSize(9);
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void reportsAmbiguousOutcomeAsUnknownAndNeverRetriesInitialize() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(new InitializationResult.OutcomeUnknown());

        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.authorizationUrl").value(nullValue()))
                .andExpect(jsonPath("$.message").isNotEmpty());

        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        verify(paystack, times(1)).initialize(any());
        assertThat(payments.findByReference(reference).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void treatsDuplicateReferenceAtPaystackAsUnknown() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(new InitializationResult.Rejected(RejectionReason.DUPLICATE_REFERENCE, "Duplicate Transaction Reference"));

        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @ParameterizedTest
    @CsvSource({"BAD_REQUEST", "UNAUTHORIZED"})
    void mapsProviderRejectionToBadGatewayAndFailsPayment(RejectionReason reason) throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(new InitializationResult.Rejected(reason, "Currency not supported by merchant"));

        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("provider-rejected"))
                .andExpect(jsonPath("$.detail").value("The payment provider rejected the request"));

        var payment = payments.findByReference(reference).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getGatewayResponse()).isEqualTo("Currency not supported by merchant");
    }

    @Test
    void mapsProviderUnavailabilityToServiceUnavailableAndFailsPayment() throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(new InitializationResult.Unavailable());

        initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("provider-unavailable"));

        assertThat(payments.findByReference(reference).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void neverLeaksSecretsInLogsOrResponses(CapturedOutput output) throws Exception {
        var reference = uniqueReference();
        when(paystack.initialize(any())).thenReturn(INITIALIZED);

        var created = initiate("client-a", body(reference, "jane@example.com", "150.00", "GHS"))
                .andReturn().getResponse().getContentAsString();
        when(paystack.initialize(any())).thenReturn(new InitializationResult.Rejected(RejectionReason.UNAUTHORIZED, "Invalid key"));
        var rejected = initiate("client-a", body(uniqueReference(), "jane@example.com", "150.00", "GHS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(created + rejected).doesNotContain("sk_test_dummy").doesNotContain("Exception").doesNotContain("Invalid key");
        assertThat(output.getAll()).doesNotContain("sk_test_dummy").doesNotContain("jane@example.com");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post(PAYMENTS).contentType(MediaType.APPLICATION_JSON).content(body(uniqueReference(), "jane@example.com", "150.00", "GHS")))
                .andExpect(status().isUnauthorized());

        verify(paystack, never()).initialize(any());
    }

    private ResultActions initiate(String ownerId, String json) throws Exception {
        return mvc.perform(post(PAYMENTS)
                .with(authentication(asClient(ownerId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static UsernamePasswordAuthenticationToken asClient(String ownerId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ApiClient(ownerId, TestActors.KEY_ID, Role.CLIENT), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    private static String body(String reference, String email, String amount, String currency) {
        var amountJson = amount == null || amount.isBlank() ? "null" : amount;
        return """
                {"reference":"%s","email":"%s","amount":%s,"currency":"%s"}
                """.formatted(reference, email, amountJson, currency);
    }

    private static InitiatePaymentCommand command(String reference, String amount) {
        return InitiatePaymentCommand.builder()
                .initiator(TestActors.of("client-a"))
                .reference(reference)
                .email("jane@example.com")
                .amount(new BigDecimal(amount))
                .currency("GHS")
                .build();
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

    private static String uniqueReference() {
        return "PAY-" + UUID.randomUUID();
    }
}
