package com.techcrm.crm.common;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable response envelope for paginated endpoints — deliberately not
 *  Spring Data's raw Page JSON, which leaks pageable/sort/other internal
 *  fields and couples the frontend to Spring Data internals. */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
