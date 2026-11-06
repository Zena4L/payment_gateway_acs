package org.clement.acs_payment.payment.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.model.TransitionTrigger;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class PaymentQueryIT {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final Instant DAY_1 = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant DAY_2 = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant DAY_3 = Instant.parse("2026-11-03T09:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Test
    void pagesThroughOwnPaymentsNewestFirst() throws Exception {
        var owner = owner();
        var oldest = seed(owner, "100.00", PaymentStatus.PENDING, DAY_1);
        var middle = seed(owner, "200.00", PaymentStatus.PENDING, DAY_2);
        var newest = seed(owner, "300.00", PaymentStatus.PENDING, DAY_3);

        listAs(owner, "size", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].reference").value(contains(newest.getReference(), middle.getReference())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.sort").value("createdAt"))
                .andExpect(jsonPath("$.direction").value("desc"));

        listAs(owner, "size", "2", "page", "1")
                .andExpect(jsonPath("$.content[*].reference").value(contains(oldest.getReference())));
    }

    @Test
    void sortsByAmountAscending() throws Exception {
        var owner = owner();
        var large = seed(owner, "300.00", PaymentStatus.PENDING, DAY_1);
        var small = seed(owner, "100.00", PaymentStatus.PENDING, DAY_2);
        var medium = seed(owner, "200.00", PaymentStatus.PENDING, DAY_3);

        listAs(owner, "sort", "amount", "direction", "asc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].reference").value(contains(small.getReference(), medium.getReference(), large.getReference())));
    }

    @Test
    void sortsByStatus() throws Exception {
        var owner = owner();
        var unknown = seed(owner, "100.00", PaymentStatus.UNKNOWN, DAY_1);
        var failed = seed(owner, "100.00", PaymentStatus.FAILED, DAY_2);
        var pending = seed(owner, "100.00", PaymentStatus.PENDING, DAY_3);

        listAs(owner, "sort", "status", "direction", "asc")
                .andExpect(jsonPath("$.content[*].reference").value(contains(failed.getReference(), pending.getReference(), unknown.getReference())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=101", "size=0", "page=-1", "sort=email", "direction=sideways", "status=PAID", "createdFrom=yesterday"})
    void rejectsInvalidQueryParameters(String parameter) throws Exception {
        var pair = parameter.split("=");

        listAs(owner(), pair[0], pair[1])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void filtersByStatus() throws Exception {
        var owner = owner();
        var failed = seed(owner, "100.00", PaymentStatus.FAILED, DAY_1);
        seed(owner, "100.00", PaymentStatus.PENDING, DAY_2);

        listAs(owner, "status", "FAILED")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value(failed.getReference()));
    }

    @Test
    void filtersByReference() throws Exception {
        var owner = owner();
        var wanted = seed(owner, "100.00", PaymentStatus.PENDING, DAY_1);
        seed(owner, "100.00", PaymentStatus.PENDING, DAY_2);

        listAs(owner, "reference", wanted.getReference())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(wanted.getId().toString()));
    }

    @Test
    void filtersByCreatedDateRangeWithInclusiveStartAndExclusiveEnd() throws Exception {
        var owner = owner();
        seed(owner, "100.00", PaymentStatus.PENDING, DAY_1);
        var inRange = seed(owner, "100.00", PaymentStatus.PENDING, DAY_2);
        seed(owner, "100.00", PaymentStatus.PENDING, DAY_3);

        listAs(owner, "createdFrom", DAY_2.toString(), "createdTo", DAY_3.toString())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value(inRange.getReference()));
    }

    @Test
    void combinesFilters() throws Exception {
        var owner = owner();
        seed(owner, "100.00", PaymentStatus.FAILED, DAY_1);
        var match = seed(owner, "100.00", PaymentStatus.FAILED, DAY_2);
        seed(owner, "100.00", PaymentStatus.PENDING, DAY_2);

        listAs(owner, "status", "FAILED", "createdFrom", DAY_2.toString())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value(match.getReference()));
    }

    @Test
    void rejectsInvertedDateRange() throws Exception {
        listAs(owner(), "createdFrom", DAY_3.toString(), "createdTo", DAY_1.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-date-range"));
    }

    @Test
    void returnsSingleTransactionWithItsDetails() throws Exception {
        var owner = owner();
        var payment = seed(owner, "150.00", PaymentStatus.PENDING, DAY_1);

        getAs(owner, Role.CLIENT, payment.getId().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.reference").value(payment.getReference()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.currency").value("GHS"))
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.authorizationUrl").value("https://checkout.paystack.com/abc"))
                .andExpect(jsonPath("$.createdAt").value(DAY_1.toString()));
    }

    @Test
    void returnsNotFoundForUnknownTransaction() throws Exception {
        getAs(owner(), Role.CLIENT, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment-not-found"));
    }

    @Test
    void rejectsMalformedTransactionId() throws Exception {
        getAs(owner(), Role.CLIENT, "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PAYMENTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(PAYMENTS + "/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void showsClientsOnlyTheirOwnPayments() throws Exception {
        var mine = owner();
        var theirs = owner();
        var myPayment = seed(mine, "100.00", PaymentStatus.PENDING, DAY_1);
        var theirPayment = seed(theirs, "100.00", PaymentStatus.PENDING, DAY_1);

        listAs(mine, "size", "100")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].reference").value(everyItem(not(theirPayment.getReference()))))
                .andExpect(jsonPath("$.content[*].reference").value(hasItem(myPayment.getReference())));
        listAs(mine, "reference", theirPayment.getReference())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void hidesOtherClientsTransactionBehindNotFound() throws Exception {
        var theirPayment = seed(owner(), "100.00", PaymentStatus.PENDING, DAY_1);

        getAs(owner(), Role.CLIENT, theirPayment.getId().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment-not-found"));
    }

    @Test
    void letsAdminSeeEveryClientsPayments() throws Exception {
        var first = seed(owner(), "100.00", PaymentStatus.PENDING, DAY_1);
        var second = seed(owner(), "100.00", PaymentStatus.PENDING, DAY_1);

        mvc.perform(get(PAYMENTS).param("createdFrom", DAY_1.toString()).param("createdTo", DAY_2.toString()).param("size", "100")
                        .with(authentication(principal("bootstrap-admin", Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].reference").value(hasItems(first.getReference(), second.getReference())));
        getAs("bootstrap-admin", Role.ADMIN, second.getId().toString()).andExpect(status().isOk());
    }

    @Test
    void neverExposesInternalOrSensitiveFields() throws Exception {
        var owner = owner();
        var payment = seed(owner, "100.00", PaymentStatus.FAILED, DAY_1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getGatewayResponse()).isEqualTo("Declined by issuer");

        var single = getAs(owner, Role.CLIENT, payment.getId().toString()).andReturn().getResponse().getContentAsString();
        var list = listAs(owner).andReturn().getResponse().getContentAsString();

        for (var body : List.of(single, list)) {
            assertThat(body).doesNotContain("gatewayResponse").doesNotContain("Declined by issuer")
                    .doesNotContain("ownerId").doesNotContain(owner).doesNotContain("accessCode").doesNotContain("version");
        }
    }

    private ResultActions listAs(String ownerId, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get(PAYMENTS).with(authentication(principal(ownerId, Role.CLIENT)));
        for (int i = 0; i < parameters.length; i += 2) {
            request = request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions getAs(String ownerId, Role role, String id) throws Exception {
        return mvc.perform(get(PAYMENTS + "/{id}", id).with(authentication(principal(ownerId, role))));
    }

    private static UsernamePasswordAuthenticationToken principal(String ownerId, Role role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ApiClient(ownerId, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    private Payment seed(String ownerId, String amount, PaymentStatus status, Instant createdAt) {
        var clock = Clock.fixed(createdAt, ZoneOffset.UTC);
        var money = new Money(new BigDecimal(amount), Currency.getInstance("GHS"));
        var payment = Payment.initialize("PAY-" + UUID.randomUUID(), ownerId, "jane@example.com", money, clock);
        switch (status) {
            case PENDING -> payment.markInitialized("https://checkout.paystack.com/abc", clock);
            case UNKNOWN -> payment.markOutcomeUnknown(clock);
            case FAILED -> payment.markFailed("Declined by issuer", TransitionTrigger.INITIATE, clock);
            default -> throw new IllegalArgumentException("Unsupported seed status " + status);
        }
        return payments.save(payment);
    }

    private static String owner() {
        return "client-" + UUID.randomUUID();
    }
}
