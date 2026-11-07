package org.clement.acs_payment.security.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.security.internal.filter.ApiKeyAuthenticationFilter;
import org.clement.acs_payment.security.internal.model.ApiKey;
import org.clement.acs_payment.security.internal.repository.ApiClientKeyRepository;
import org.clement.acs_payment.security.internal.service.BootstrapAdminKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@Import(WhoAmIController.class)
class ApiKeyAuthenticationIT {

    private static final String ADMIN_KEYS = "/api/v1/admin/api-keys";

    @Autowired
    MockMvc mvc;

    @Autowired
    ApiClientKeyRepository keys;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${acs.security.bootstrap-admin-key}")
    String bootstrapAdminKey;

    @Test
    void rejectsRequestWithoutKey() throws Exception {
        mvc.perform(get("/test/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void rejectsMalformedKey() throws Exception {
        mvc.perform(get("/test/whoami").header(ApiKeyAuthenticationFilter.HEADER, "garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void rejectsWellFormedKeyThatWasNeverIssued() throws Exception {
        mvc.perform(get("/test/whoami").header(ApiKeyAuthenticationFilter.HEADER, ApiKey.generate().value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsKnownKeyIdWithWrongSecret() throws Exception {
        var issued = issueKey("client-wrong-secret", "CLIENT");
        var forged = new ApiKey(ApiKey.parse(issued).orElseThrow().keyId(), ApiKey.generate().secret());

        mvc.perform(get("/test/whoami").header(ApiKeyAuthenticationFilter.HEADER, forged.value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatesBootstrapAdminFromEnvironment() throws Exception {
        mvc.perform(get("/test/whoami").header(ApiKeyAuthenticationFilter.HEADER, bootstrapAdminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(BootstrapAdminKey.OWNER_ID))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void issuedKeyAuthenticatesAsItsClientWithoutExposingTheKey() throws Exception {
        var apiKey = issueKey("client-a", "CLIENT");
        var parsed = ApiKey.parse(apiKey).orElseThrow();

        var body = mvc.perform(get("/test/whoami").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("client-a"))
                .andExpect(jsonPath("$.keyId").value(parsed.keyId()))
                .andExpect(jsonPath("$.role").value("CLIENT"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(apiKey).doesNotContain(parsed.secret());
    }

    @Test
    void storesOnlyTheHashOfAnIssuedKey() throws Exception {
        var apiKey = issueKey("client-hash", "CLIENT");
        var parsed = ApiKey.parse(apiKey).orElseThrow();

        var stored = keys.findByKeyId(parsed.keyId()).orElseThrow();

        assertThat(stored.getKeyHash()).isEqualTo(parsed.hash()).doesNotContain(parsed.secret());
        assertThat(jdbc.queryForObject("select count(*) from api_client_key where position(? in key_hash || owner_id || key_id) > 0",
                Long.class, parsed.secret())).isZero();
    }

    @Test
    void forbidsClientKeyOnAdminRoutes() throws Exception {
        var clientKey = issueKey("client-b", "CLIENT");

        mvc.perform(post(ADMIN_KEYS)
                        .header(ApiKeyAuthenticationFilter.HEADER, clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"client-x\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void revokedKeyStopsAuthenticating() throws Exception {
        var apiKey = issueKey("client-c", "CLIENT");
        var keyId = ApiKey.parse(apiKey).orElseThrow().keyId();

        mvc.perform(delete(ADMIN_KEYS + "/" + keyId).header(ApiKeyAuthenticationFilter.HEADER, bootstrapAdminKey))
                .andExpect(status().isNoContent());

        mvc.perform(get("/test/whoami").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokingUnknownKeyReturnsNotFound() throws Exception {
        mvc.perform(delete(ADMIN_KEYS + "/000000000000").header(ApiKeyAuthenticationFilter.HEADER, bootstrapAdminKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api-key-not-found"));
    }

    @Test
    void validatesIssueRequest() throws Exception {
        mvc.perform(post(ADMIN_KEYS)
                        .header(ApiKeyAuthenticationFilter.HEADER, bootstrapAdminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"\",\"role\":\"CLIENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("ownerId"));
    }

    @Test
    void leavesWebhookEndpointToSignatureCheckInsteadOfApiKeys() throws Exception {
        mvc.perform(post("/api/v1/webhooks/paystack").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid-signature"));
    }

    private String issueKey(String ownerId, String role) throws Exception {
        var response = mvc.perform(post(ADMIN_KEYS)
                        .header(ApiKeyAuthenticationFilter.HEADER, bootstrapAdminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + ownerId + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value(matchesPattern(ApiKey.FORMAT)))
                .andExpect(jsonPath("$.ownerId").value(ownerId))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.apiKey");
    }
}
