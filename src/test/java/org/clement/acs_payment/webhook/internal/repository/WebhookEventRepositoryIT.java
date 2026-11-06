package org.clement.acs_payment.webhook.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import org.clement.acs_payment.TestcontainersConfiguration;
import org.clement.acs_payment.paystack.WebhookPayload;
import org.clement.acs_payment.webhook.internal.model.ProcessingState;
import org.clement.acs_payment.webhook.internal.model.WebhookEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookEventRepositoryIT {

    private static final Clock CLOCK = Clock.systemUTC();

    @Autowired
    WebhookEventRepository events;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allowsOnlyOneNonDuplicateEventPerDedupKey() {
        var charge = charge();
        events.saveAndFlush(WebhookEvent.received(charge, CLOCK));

        assertThatThrownBy(() -> events.saveAndFlush(WebhookEvent.received(charge, CLOCK)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_webhook_event_dedup");
    }

    @Test
    void keepsEveryDuplicateDelivery() {
        var charge = charge();
        events.saveAndFlush(WebhookEvent.received(charge, CLOCK));

        events.saveAndFlush(WebhookEvent.duplicate(charge, CLOCK));
        events.saveAndFlush(WebhookEvent.duplicate(charge, CLOCK));

        assertThat(events.findByDedupKeyOrderByReceivedAtAsc(WebhookEvent.dedupKey(charge)))
                .extracting(WebhookEvent::getProcessingState)
                .containsExactlyInAnyOrder(ProcessingState.RECEIVED, ProcessingState.DUPLICATE, ProcessingState.DUPLICATE);
    }

    @Test
    void rejectsUnknownProcessingState() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into webhook_event (id, event_type, processing_state, received_at)
                values (?, 'charge.success', 'DONE', now())
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_webhook_event_processing_state");
    }

    private static WebhookPayload.ChargeSuccess charge() {
        return new WebhookPayload.ChargeSuccess("charge.success", Math.abs(UUID.randomUUID().getMostSignificantBits()),
                "PAY-" + UUID.randomUUID(), 15000, "GHS");
    }
}
