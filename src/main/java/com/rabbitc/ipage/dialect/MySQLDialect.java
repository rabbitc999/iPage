package com.rabbitc.ipage.dialect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/**
 * MySQL / MariaDB 方言。
 * <p>
 * 标识符不加引号（反引号在跨库时更容易出问题，而框架生成的别名都是小写字母数字），
 * 自增主键用 {@code AUTO_INCREMENT}。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class MySQLDialect implements Dialect {

    /**
     * 单例。
     */
    public static final MySQLDialect INSTANCE = new MySQLDialect();

    private MySQLDialect() {
    }

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public String idColumnType(Class<?> javaType, boolean autoIncrement) {
        String base = columnType(javaType);
        return autoIncrement ? base + " NOT NULL AUTO_INCREMENT PRIMARY KEY" : base + " PRIMARY KEY";
    }

    @Override
    public String columnType(Class<?> javaType) {
        Class<?> type = javaType == null ? String.class : javaType;
        if (type == int.class || type == Integer.class) {
            return "INT";
        }
        if (type == long.class || type == Long.class) {
            return "BIGINT";
        }
        if (type == short.class || type == Short.class) {
            return "SMALLINT";
        }
        if (type == byte.class || type == Byte.class) {
            return "TINYINT";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "TINYINT(1)";
        }
        if (type == double.class || type == Double.class) {
            return "DOUBLE";
        }
        if (type == float.class || type == Float.class) {
            return "FLOAT";
        }
        if (type == BigDecimal.class) {
            return "DECIMAL(18, 4)";
        }
        if (type == BigInteger.class) {
            return "DECIMAL(20, 0)";
        }
        if (type == LocalDate.class) {
            return "DATE";
        }
        if (type == LocalTime.class) {
            return "TIME";
        }
        if (type == LocalDateTime.class || type == Instant.class || type == Date.class) {
            return "DATETIME";
        }
        if (type.isEnum()) {
            return "VARCHAR(64)";
        }
        return "VARCHAR(255)";
    }

    @Override
    public String tableExistsQuery(String table) {
        return "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()"
               + " AND table_name = " + literal(table);
    }

    @Override
    public String columnsQuery(String table) {
        return "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE()"
               + " AND table_name = " + literal(table) + " ORDER BY ordinal_position";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
