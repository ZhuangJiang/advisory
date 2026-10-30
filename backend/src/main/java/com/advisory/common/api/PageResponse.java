package com.advisory.common.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Envelope payload for paged collections. {@code page} is zero-based; {@code size} defaults to 20
 * and is capped at 100 by the endpoints that accept it.
 */
public record PageResponse<T>(List<T> content, int page, int size, long total, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }
}
