package com.rabbitc.ipage.jdbc;

/**
 * 数据访问异常。
 * <p>
 * 把 {@link java.sql.SQLException} 统一包装成非受检异常，避免框架的每个方法都抛 {@code throws}，
 * 同时保留原始 SQL 以便排查。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public class IpagDataAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sql;

    /**
     * 构造异常。
     *
     * @param message 说明
     * @param sql     出错的 SQL，未知时为 {@code null}
     * @param cause   原始异常
     */
    public IpagDataAccessException(String message, String sql, Throwable cause) {
        super(message, cause);
        this.sql = sql;
    }

    /**
     * 出错的 SQL。
     *
     * @return SQL，未知时为 {@code null}
     */
    public String sql() {
        return sql;
    }
}
