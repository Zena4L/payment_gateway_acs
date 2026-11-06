package org.clement.acs_payment.payment.internal.service;

import java.util.ArrayList;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.clement.acs_payment.payment.internal.exception.InvalidDateRangeException;
import org.clement.acs_payment.payment.internal.exception.PaymentNotFoundException;
import org.clement.acs_payment.payment.internal.model.Payment;
import org.clement.acs_payment.payment.internal.repository.PaymentRepository;
import org.clement.acs_payment.payment.internal.repository.PaymentSpecifications;
import org.clement.acs_payment.security.ApiClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

    private static final String TIE_BREAKER = "id";

    private final PaymentRepository payments;

    public Page<Payment> list(PaymentQuery query, ApiClient caller) {
        if (query.createdFrom() != null && query.createdTo() != null && !query.createdFrom().isBefore(query.createdTo())) {
            throw new InvalidDateRangeException();
        }
        var direction = query.direction().getSpringDirection();
        var sort = Sort.by(direction, query.sort().getProperty()).and(Sort.by(direction, TIE_BREAKER));
        return payments.findAll(specification(query, caller), PageRequest.of(query.page(), query.size(), sort));
    }

    public Payment get(UUID id, ApiClient caller) {
        return payments.findById(id)
                .filter(payment -> caller.isAdmin() || payment.getOwnerId().equals(caller.ownerId()))
                .orElseThrow(PaymentNotFoundException::new);
    }

    private static Specification<Payment> specification(PaymentQuery query, ApiClient caller) {
        var filters = new ArrayList<Specification<Payment>>();
        if (!caller.isAdmin()) {
            filters.add(PaymentSpecifications.ownedBy(caller.ownerId()));
        }
        if (query.status() != null) {
            filters.add(PaymentSpecifications.hasStatus(query.status()));
        }
        if (query.reference() != null) {
            filters.add(PaymentSpecifications.hasReference(query.reference()));
        }
        if (query.createdFrom() != null) {
            filters.add(PaymentSpecifications.createdOnOrAfter(query.createdFrom()));
        }
        if (query.createdTo() != null) {
            filters.add(PaymentSpecifications.createdBefore(query.createdTo()));
        }
        return Specification.allOf(filters);
    }
}
