package com.advisory.common.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 分页集合在响应信封中的载荷。
 *
 * <p>{@code page} 从 0 开始计数；{@code size} 默认 20，接受该参数的接口统一将其上限限制为 100，
 * 防止一次请求拉走整表。</p>
 *
 * @param content    当前页数据
 * @param page       当前页码，从 0 开始
 * @param size       每页条数
 * @param total      满足条件的总条数
 * @param totalPages 总页数，由 {@code total} 与 {@code size} 推导
 * @param <T>        元素类型
 */
public record PageResponse<T>(List<T> content, int page, int size, long total, int totalPages) {

    /**
     * 由 Spring Data 的 {@link Page} 转换而来。
     *
     * @param page 查询结果页
     * @return 分页载荷
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * 由已查询好的列表直接构造，供不使用 {@link Page} 的 JDBC 查询复用。
     *
     * @param content 当前页数据
     * @param page    当前页码，从 0 开始
     * @param size    每页条数，非正数时总页数记为 0
     * @param total   满足条件的总条数
     * @return 分页载荷
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }
}
