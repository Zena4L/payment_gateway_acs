package org.clement.acs_payment.payment.internal.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort,
        String direction) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper, String sort, String direction) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                sort,
                direction);
    }
}
