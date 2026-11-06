package org.clement.acs_payment.payment.internal.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.clement.acs_payment.shared.UuidV7;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "payment_transition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class PaymentTransition {

    @Id
    private UUID id;

    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    private @Nullable PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private PaymentStatus toStatus;

    @Enumerated(EnumType.STRING)
    private TransitionTrigger triggeredBy;

    private Instant occurredAt;

    private @Nullable String actor;

    private @Nullable String reason;

    public static PaymentTransition from(PaymentTransitioned event) {
        return PaymentTransition.builder()
                .id(UuidV7.next())
                .paymentId(event.paymentId())
                .fromStatus(event.from())
                .toStatus(event.to())
                .triggeredBy(event.trigger())
                .occurredAt(event.occurredAt())
                .actor(event.actor())
                .reason(event.reason())
                .build();
    }
}
