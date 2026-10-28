package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.ShopProperties;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/** Keeps page numbers and sizes inside safe limits (a caller must not ask for a million rows). */
@Component
public class Paging {

    private final int defaultSize;
    private final int maxSize;

    public Paging(ShopProperties shop) {
        this.defaultSize = shop.defaultPageSize();
        this.maxSize = shop.maxPageSize();
    }

    public Pageable of(Integer page, Integer size, Sort sort) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? defaultSize : Math.min(size, maxSize);
        return PageRequest.of(safePage, safeSize, sort);
    }

    public <T, R> PageResponse<R> map(Page<T> page, Function<T, R> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
