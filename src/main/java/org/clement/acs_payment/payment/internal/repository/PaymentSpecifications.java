package org.clement.acs_payment.payment.internal.repository;

import java.time.Instant;
import lombok.experimental.UtilityClass;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.model.PaymentStatus;
import org.springframework.data.jpa.domain.Specification;

@UtilityClass
public class PaymentSpecifications {

    public static Specification<Payment> ownedBy(String ownerId) {
        return (root, query, builder) -> builder.equal(root.get("ownerId"), ownerId);
    }

    public static Specification<Payment> hasStatus(PaymentStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<Payment> hasReference(String reference) {
        return (root, query, builder) -> builder.equal(root.get("reference"), reference);
    }

    public static Specification<Payment> createdOnOrAfter(Instant from) {
        return (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Payment> createdBefore(Instant to) {
        return (root, query, builder) -> builder.lessThan(root.get("createdAt"), to);
    }
}
