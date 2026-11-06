package org.clement.acs_payment.payment.internal.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;

@Getter
@RequiredArgsConstructor
public enum SortDirection {
    asc(Sort.Direction.ASC),
    desc(Sort.Direction.DESC);

    private final Sort.Direction springDirection;
}
