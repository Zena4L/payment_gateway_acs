package org.clement.acs_payment.shared.internal.exception;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.shared.CorrelationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@Import(ErrorScenariosController.class)
class ApiExceptionHandlerIT {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser
    void mapsApiExceptionToStandardBody() throws Exception {
        mvc.perform(get("/test/errors/api-exception").header(CorrelationId.HEADER, "trace-1"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("payment-conflict"))
                .andExpect(jsonPath("$.detail").value("Reference already used with different details"))
                .andExpect(jsonPath("$.instance").value("/test/errors/api-exception"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.correlationId").value("trace-1"))
                .andExpect(header().string(CorrelationId.HEADER, "trace-1"));
    }

    @Test
    @WithMockUser
    void returnsFieldErrorsForInvalidBody() throws Exception {
        mvc.perform(post("/test/errors/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("email", "amount")))
                .andExpect(jsonPath("$.correlationId").value(notNullValue()));
    }

    @Test
    @WithMockUser
    void hidesParserDetailsForMalformedJson() throws Exception {
        mvc.perform(post("/test/errors/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad-request"))
                .andExpect(content().string(not(containsString("tools.jackson"))));
    }

    @Test
    @WithMockUser
    void hidesInternalDetailsForUnexpectedErrors() throws Exception {
        mvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andExpect(content().string(not(containsString("boom"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @WithMockUser
    void usesStandardBodyForUnknownRoute() throws Exception {
        mvc.perform(get("/test/errors/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"))
                .andExpect(jsonPath("$.correlationId").value(notNullValue()));
    }

    @Test
    @WithMockUser
    void usesStandardBodyForUnsupportedMethod() throws Exception {
        mvc.perform(delete("/test/errors/api-exception"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method-not-allowed"));
    }

    @Test
    void usesStandardBodyWhenUnauthenticated() throws Exception {
        mvc.perform(get("/test/errors/api-exception").header(CorrelationId.HEADER, "trace-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.correlationId").value("trace-401"));
    }
}
