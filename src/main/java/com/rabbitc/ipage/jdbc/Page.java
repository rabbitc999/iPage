package com.rabbitc.ipage.jdbc;

import java.util.List;

/**
 * 一页查询结果。
 * <p>
 * 不可变对象：请求线程拿到的分页数据不会被其它请求改写。旧实现把当前页、总页数挂在
 * 静态的会话上下文里，并发下会串页，这里不再复用那种做法。
 *
 * @param <T>       行类型
 * @param rows      当前页数据
 * @param total     总行数
 * @param page      当前页码，从 1 开始
 * @param pageSize  每页行数
 * @param <T>       行类型
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class Page<T> {

    private final List<T> rows;
    private final long total;
    private final int page;
    private final int pageSize;

    /**
     * 构造分页结果。
     *
     * @param rows     当前页数据
     * @param total    总行数
     * @param page     当前页码
     * @param pageSize 每页行数
     */
    public Page(List<T> rows, long total, int page, int pageSize) {
        this.rows = List.copyOf(rows);
        this.total = Math.max(total, 0);
        this.pageSize = Math.max(pageSize, 1);
        this.page = Math.max(page, 1);
    }

    /**
     * 当前页数据。
     *
     * @return 行列表
     */
    public List<T> rows() {
        return rows;
    }

    /**
     * 总行数。
     *
     * @return 总行数
     */
    public long total() {
        return total;
    }

    /**
     * 当前页码，从 1 开始。
     *
     * @return 页码
     */
    public int page() {
        return page;
    }

    /**
     * 每页行数。
     *
     * @return 每页行数
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     * 总页数，无数据时为 0。
     *
     * @return 总页数
     */
    public int totalPages() {
        return (int) ((total + pageSize - 1) / pageSize);
    }

    /**
     * 是否还有上一页。
     *
     * @return 有则 {@code true}
     */
    public boolean hasPrevious() {
        return page > 1;
    }

    /**
     * 是否还有下一页。
     *
     * @return 有则 {@code true}
     */
    public boolean hasNext() {
        return page < totalPages();
    }

    /**
     * 是否为空页。
     *
     * @return 空则 {@code true}
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * 第一页的页码。
     *
     * @return 页码
     */
    public int firstPage() {
        return 1;
    }

    /**
     * 末页的页码。
     *
     * @return 页码
     */
    public int lastPage() {
        return Math.max(totalPages(), 1);
    }

    @Override
    public String toString() {
        return "Page[" + page + "/" + totalPages() + ", size=" + rows.size() + ", total=" + total + "]";
    }
}
