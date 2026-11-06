package org.clement.acs_payment.shared.internal.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.clement.acs_payment.shared.CorrelationId;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final AtomicReference<String> idSeenByChain = new AtomicReference<>();
    private final MockFilterChain chain = new MockFilterChain(new HttpServlet() {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            idSeenByChain.set(MDC.get(CorrelationId.MDC_KEY));
        }
    });

    @Test
    void generatesIdWhenHeaderIsMissing() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getHeader(CorrelationId.HEADER)).isNotBlank();
        assertThat(idSeenByChain.get()).isEqualTo(response.getHeader(CorrelationId.HEADER));
    }

    @Test
    void reusesValidIncomingId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "client-trace-42");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("client-trace-42");
        assertThat(idSeenByChain.get()).isEqualTo("client-trace-42");
    }

    @Test
    void replacesInvalidIncomingId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "bad id\nwith newline");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationId.HEADER)).isNotEqualTo("bad id\nwith newline").matches("[A-Za-z0-9-]+");
    }

    @Test
    void replacesOverlongIncomingId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "a".repeat(65));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationId.HEADER)).hasSizeLessThan(65);
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
}
