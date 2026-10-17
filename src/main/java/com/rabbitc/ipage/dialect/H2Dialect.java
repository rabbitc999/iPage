package com.rabbitc.ipage.dialect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/**
 * H2 方言，兼作内存库与测试用库。
 * <p>
 * H2 的类型多数与 PostgreSQL 同名，差异在自增主键用 {@code AUTO_INCREMENT}（兼容模式除外），
 * 以及系统表名是 {@code INFORMATION_SCHEMA}。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class H2Dialect implements Dialect {

    /**
     * 单例。
     */
    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
    }

    @Override
    public String name() {
        return "H2";
    }

    @Override
    public String idColumnType(Class<?> javaType, boolean autoIncrement) {
        if (autoIncrement) {
            if (javaType == Long.class || javaType == long.class) {
                return "BIGINT AUTO_INCREMENT PRIMARY KEY";
            }
            if (javaType == Integer.class || javaType == int.class) {
                return "INT AUTO_INCREMENT PRIMARY KEY";
            }
        }
        return columnType(javaType) + " PRIMARY KEY";
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
        if (type == short.class || type == Short.class || type == byte.class || type == Byte.class) {
            return "SMALLINT";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "BOOLEAN";
        }
        if (type == double.class || type == Double.class) {
            return "DOUBLE";
        }
        if (type == float.class || type == Float.class) {
            return "REAL";
        }
        if (type == BigDecimal.class) {
            return "NUMERIC(18, 4)";
        }
        if (type == BigInteger.class) {
            return "NUMERIC(20, 0)";
        }
        if (type == LocalDate.class) {
            return "DATE";
        }
        if (type == LocalTime.class) {
            return "TIME";
        }
        if (type == LocalDateTime.class || type == Instant.class || type == Date.class) {
            return "TIMESTAMP";
        }
        if (type.isEnum()) {
            return "VARCHAR(64)";
        }
        return "VARCHAR(255)";
    }

    @Override
    public String tableExistsQuery(String table) {
        return "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = SCHEMA()"
               + " AND UPPER(TABLE_NAME) = UPPER(" + literal(table) + ")";
    }

    @Override
    public String columnsQuery(String table) {
        return "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = SCHEMA()"
               + " AND UPPER(TABLE_NAME) = UPPER(" + literal(table) + ") ORDER BY ORDINAL_POSITION";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
