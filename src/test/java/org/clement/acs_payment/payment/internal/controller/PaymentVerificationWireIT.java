package org.clement.acs_payment.payment.internal.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.Money;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
class PaymentVerificationWireIT {

    @RegisterExtension
    static WireMockExtension paystack = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void paystackUrl(DynamicPropertyRegistry registry) {
        registry.add("acs.paystack.base-url", paystack::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    Clock clock;

    @Test
    void verifiesPaymentThroughRealPaystackClient() throws Exception {
        var reference = "PAY-" + UUID.randomUUID();
        var payment = Payment.initialize(reference, "client-a", "jane@example.com", new Money(new BigDecimal("150.00"), Currency.getInstance("GHS")), clock);
        payment.markInitialized("https://checkout.paystack.com/abc", clock);
        payments.save(payment);
        paystack.stubFor(get("/transaction/verify/" + reference).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":true,"message":"Verification successful",
                         "data":{"id":4099260516,"status":"success","reference":"%s","amount":15000,"currency":"GHS",
                                 "channel":"mobile_money","paid_at":"2026-11-06T10:15:30.000Z","gateway_response":"Approved"}}
                        """.formatted(reference))));

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payments/verify/{reference}", reference)
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                new ApiClient("client-a", Role.CLIENT), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.channel").value("mobile_money"));

        paystack.verify(1, getRequestedFor(urlEqualTo("/transaction/verify/" + reference))
                .withHeader("Authorization", equalTo("Bearer sk_test_dummy")));
        assertThat(payments.findByReference(reference).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }
}
