package org.clement.acs_payment.payment.internal.service;

import java.time.Instant;
import lombok.Builder;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.jspecify.annotations.Nullable;

@Builder
public record PaymentQuery(
        int page,
        int size,
        PaymentSortField sort,
        SortDirection direction,
        @Nullable PaymentStatus status,
        @Nullable String reference,
        @Nullable Instant createdFrom,
        @Nullable Instant createdTo) {
}
