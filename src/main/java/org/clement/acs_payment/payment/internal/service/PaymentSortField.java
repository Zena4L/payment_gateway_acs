package org.clement.acs_payment.payment.internal.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentSortField {
    createdAt("createdAt"),
    amount("amount"),
    status("status");

    private final String property;
}
