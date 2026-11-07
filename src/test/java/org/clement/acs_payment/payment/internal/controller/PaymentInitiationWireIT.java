package org.clement.acs_payment.payment.internal.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import java.util.UUID;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.payment.internal.model.TestActors;
import org.clement.acs_payment.security.ApiClient;
import org.clement.acs_payment.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentInitiationWireIT {

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
    JdbcTemplate jdbc;

    @Test
    void initiatesPaymentThroughRealPaystackClient(CapturedOutput output) throws Exception {
        var reference = "PAY-" + UUID.randomUUID();
        paystack.stubFor(post("/transaction/initialize").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":true,"message":"Authorization URL created","raw-marker":"do-not-log",
                         "data":{"authorization_url":"https://checkout.paystack.com/wire","access_code":"wire-code","reference":"%s"}}
                        """.formatted(reference))));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments/initiate")
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                new ApiClient("client-a", TestActors.KEY_ID, Role.CLIENT), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference":"%s","email":"jane@example.com","amount":150.00,"currency":"GHS"}
                                """.formatted(reference)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.authorizationUrl").value("https://checkout.paystack.com/wire"));

        paystack.verify(1, postRequestedFor(urlEqualTo("/transaction/initialize"))
                .withHeader("Authorization", equalTo("Bearer sk_test_dummy"))
                .withRequestBody(equalToJson("""
                        {"email":"jane@example.com","amount":15000,"currency":"GHS","reference":"%s"}
                        """.formatted(reference))));
        assertThat(output.getAll()).doesNotContain("do-not-log").doesNotContain("wire-code").doesNotContain("sk_test_dummy");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'payment' and column_name = 'access_code'", Long.class)).isZero();
    }
}
