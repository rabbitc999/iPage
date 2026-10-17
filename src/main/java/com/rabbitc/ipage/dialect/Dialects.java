package com.rabbitc.ipage.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * 按连接的产品名挑选方言。
 * <p>
 * 只认产品名，不猜版本：框架用到的语法在 PostgreSQL 9.5+ / MySQL 5.7+ / H2 1.4+ 上都成立。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class Dialects {

    private Dialects() {
    }

    /**
     * 按连接探测方言。
     *
     * @param connection 连接
     * @return 方言；未识别的数据库退回 {@link MySQLDialect}（最宽松的一种）
     * @throws SQLException 读取元数据失败
     */
    public static Dialect detect(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        return byProductName(meta.getDatabaseProductName());
    }

    /**
     * 按产品名挑选方言。
     *
     * @param productName 产品名，例如 {@code PostgreSQL}
     * @return 方言
     */
    public static Dialect byProductName(String productName) {
        if (productName == null) {
            return MySQLDialect.INSTANCE;
        }
        String name = productName.toLowerCase();
        if (name.contains("postgres")) {
            return PostgresDialect.INSTANCE;
        }
        if (name.startsWith("h2")) {
            return H2Dialect.INSTANCE;
        }
        return MySQLDialect.INSTANCE;
    }
}
