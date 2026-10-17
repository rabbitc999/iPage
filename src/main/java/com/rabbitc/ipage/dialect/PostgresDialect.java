package com.rabbitc.ipage.dialect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/**
 * PostgreSQL 方言。
 * <p>
 * 与 MySQL 的三处实质差异在这里处理：
 * <ul>
 *     <li>标识符必须加双引号，否则大小写会被折叠；</li>
 *     <li>自增主键用 {@code SERIAL} / {@code BIGSERIAL}，而不是 {@code AUTO_INCREMENT}；</li>
 *     <li>布尔、时间戳的类型名与取值范围不同（{@code TIMESTAMP} 不带时区，{@code TIMESTAMPTZ} 带）。</li>
 * </ul>
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class PostgresDialect implements Dialect {

    /**
     * 单例。
     */
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public boolean quotesIdentifiers() {
        return true;
    }

    @Override
    public String limitClause(int limit, Integer offset) {
        return offset == null ? " LIMIT ?" : " LIMIT ? OFFSET ?";
    }

    @Override
    public String idColumnType(Class<?> javaType, boolean autoIncrement) {
        if (autoIncrement) {
            if (javaType == Long.class || javaType == long.class) {
                return "BIGSERIAL PRIMARY KEY";
            }
            if (javaType == Integer.class || javaType == int.class) {
                return "SERIAL PRIMARY KEY";
            }
        }
        return columnType(javaType) + " PRIMARY KEY";
    }

    @Override
    public String columnType(Class<?> javaType) {
        Class<?> type = javaType == null ? String.class : javaType;
        if (type == int.class || type == Integer.class) {
            return "INTEGER";
        }
        if (type == long.class || type == Long.class) {
            return "BIGINT";
        }
        if (type == short.class || type == Short.class) {
            return "SMALLINT";
        }
        if (type == byte.class || type == Byte.class) {
            return "SMALLINT";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "BOOLEAN";
        }
        if (type == double.class || type == Double.class) {
            return "DOUBLE PRECISION";
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
        if (type == LocalDateTime.class) {
            return "TIMESTAMP";
        }
        if (type == Instant.class) {
            return "TIMESTAMPTZ";
        }
        if (type == Date.class) {
            return "TIMESTAMP";
        }
        if (type.isEnum()) {
            // 枚举按常量名落库，用定长文本即可；实测枚举常量的长度通常远小于 64
            return "VARCHAR(64)";
        }
        return "VARCHAR(255)";
    }

    @Override
    public String tableExistsQuery(String table) {
        return "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
               + " AND table_name = " + literal(table);
    }

    @Override
    public String columnsQuery(String table) {
        return "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema()"
               + " AND table_name = " + literal(table) + " ORDER BY ordinal_position";
    }

    /**
     * 用单引号字面量表示字符串，仅用于系统目录查询里的表名（表名来自注解，不是请求参数）。
     *
     * @param value 值
     * @return 字面量
     */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
