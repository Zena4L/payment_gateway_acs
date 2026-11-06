package org.clement.acs_payment.webhook.internal.repository;

import java.util.List;
import java.util.UUID;
import org.clement.acs_payment.webhook.internal.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    List<WebhookEvent> findByDedupKeyOrderByReceivedAtAsc(String dedupKey);
}
