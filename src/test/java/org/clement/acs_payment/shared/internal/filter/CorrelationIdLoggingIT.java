package org.clement.acs_payment.shared.internal.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.clement.acs_payment.IntegrationTest;
import org.clement.acs_payment.shared.CorrelationId;
import org.clement.acs_payment.shared.internal.exception.ErrorScenariosController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@Import(ErrorScenariosController.class)
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdLoggingIT {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser
    void includesCorrelationIdInRequestLogLines(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/errors/logged").header(CorrelationId.HEADER, "trace-log-7"))
                .andExpect(status().isOk());

        assertThat(output.getAll())
                .containsPattern("\\[trace-log-7\\].*handling logged request");
    }
}
