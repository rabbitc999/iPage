package com.rabbitc.ipage.reflect;

import com.rabbitc.ipage.annotation.ModelEnum;
import com.rabbitc.ipage.exception.ModelDefinitionException;
import com.rabbitc.ipage.exception.CheckerException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 值转换器：字符串与 Java 类型之间的双向转换。
 * <p>
 * 覆盖字符串、全部数值类型、布尔、枚举、日期时间以及它们的包装类型。
 * 转换失败抛出 {@link CheckerException}，让调用方能够把「格式不对」
 * 当成用户输入错误处理，而不是 500。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class ValueConverter {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    );

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日")
    );

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            int.class, Integer.class,
            long.class, Long.class,
            short.class, Short.class,
            byte.class, Byte.class,
            double.class, Double.class,
            float.class, Float.class,
            boolean.class, Boolean.class,
            char.class, Character.class
    );

    private ValueConverter() {
    }

    /**
     * 装箱类型。
     *
     * @param type 类型
     * @return 装箱后的类型
     */
    public static Class<?> box(Class<?> type) {
        return BOXES.getOrDefault(type, type);
    }

    /**
     * 把字符串转换成目标类型。
     *
     * @param target 目标类型
     * @param raw    原始字符串，{@code null} 或空串返回 {@code null}
     * @return 目标类型的值
     * @throws CheckerException 无法转换
     */
    public static Object parse(Class<?> target, String raw) throws CheckerException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Class<?> type = box(target);
        try {
            if (type == String.class) {
                return value;
            }
            if (type == Integer.class) {
                return Integer.valueOf(value);
            }
            if (type == Long.class) {
                return Long.valueOf(value);
            }
            if (type == Short.class) {
                return Short.valueOf(value);
            }
            if (type == Byte.class) {
                return Byte.valueOf(value);
            }
            if (type == Double.class) {
                return Double.valueOf(value);
            }
            if (type == Float.class) {
                return Float.valueOf(value);
            }
            if (type == BigDecimal.class) {
                return new BigDecimal(value);
            }
            if (type == BigInteger.class) {
                return new BigInteger(value);
            }
            if (type == Boolean.class) {
                return parseBoolean(value);
            }
            if (type == Character.class) {
                return value.charAt(0);
            }
            if (type.isEnum()) {
                return parseEnum(type, value);
            }
            if (type == Date.class) {
                return toDate(parseDateTime(value));
            }
            if (type == LocalDate.class) {
                return parseDate(value);
            }
            if (type == LocalDateTime.class) {
                return parseDateTime(value);
            }
            if (type == LocalTime.class) {
                return LocalTime.parse(value);
            }
            if (type == Instant.class) {
                return parseDateTime(value).atZone(ZoneId.systemDefault()).toInstant();
            }
            return value;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new CheckerException("无法把「%s」转换为 %s".formatted(value, type.getSimpleName()), e);
        }
    }

    private static Boolean parseBoolean(String value) throws CheckerException {
        return switch (value.toLowerCase()) {
            case "true", "1", "on", "yes", "是" -> Boolean.TRUE;
            case "false", "0", "off", "no", "否" -> Boolean.FALSE;
            default -> throw new CheckerException("无法把「" + value + "」转换为布尔值");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseEnum(Class<?> type, String value) throws CheckerException {
        Object[] constants = type.getEnumConstants();
        if (constants == null) {
            throw new CheckerException(type.getSimpleName() + " 不是枚举类型");
        }
        for (Object constant : constants) {
            Enum<?> item = (Enum<?>) constant;
            if (item.name().equals(value)) {
                return item;
            }
        }
        // 兼容按 @ModelEnum.text 提交的表单
        for (Object constant : constants) {
            Enum<?> item = (Enum<?>) constant;
            ModelEnum annotation = annotationOf(type, item.name());
            if (annotation != null && value.equals(annotation.text())) {
                return constant;
            }
        }
        try {
            return Enum.valueOf((Class<? extends Enum>) type, value);
        } catch (IllegalArgumentException e) {
            throw new CheckerException("枚举 " + type.getSimpleName() + " 中不存在取值「" + value + "」");
        }
    }

    private static ModelEnum annotationOf(Class<?> enumType, String constantName) {
        try {
            return enumType.getDeclaredField(constantName).getAnnotation(ModelEnum.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * 解析日期，返回 {@link LocalDate}。
     *
     * @param value 文本
     * @return 日期
     * @throws CheckerException 格式不支持
     */
    public static LocalDate parseDate(String value) throws CheckerException {
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        return parseDateTime(value).toLocalDate();
    }

    /**
     * 解析日期时间，返回 {@link LocalDateTime}。
     *
     * @param value 文本
     * @return 日期时间
     * @throws CheckerException 格式不支持
     */
    public static LocalDateTime parseDateTime(String value) throws CheckerException {
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        try {
            return parseDate(value).atStartOfDay();
        } catch (CheckerException e) {
            throw new CheckerException("无法识别日期「" + value + "」", e);
        }
    }

    private static Date toDate(LocalDateTime dateTime) {
        return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 把数据库返回的值转换成字段的 Java 类型。
     * <p>
     * JDBC 驱动给出的类型五花八门（{@code java.sql.Timestamp}、{@code BigInteger}、
     * {@code Integer} 对 {@code Long} 字段……），这里统一收口，
     * 避免在映射阶段反复写 {@code instanceof}。
     *
     * @param target 字段 Java 类型
     * @param value  原始值
     * @return 转换后的值
     */
    public static Object fromDatabase(Class<?> target, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> type = box(target);
        if (type.isInstance(value)) {
            return value;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (value instanceof Number number) {
            if (type == Integer.class) {
                return number.intValue();
            }
            if (type == Long.class) {
                return number.longValue();
            }
            if (type == Short.class) {
                return number.shortValue();
            }
            if (type == Byte.class) {
                return number.byteValue();
            }
            if (type == Double.class) {
                return number.doubleValue();
            }
            if (type == Float.class) {
                return number.floatValue();
            }
            if (type == BigDecimal.class) {
                return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
            }
            if (type == BigInteger.class) {
                return number instanceof BigInteger bigInteger ? bigInteger : BigInteger.valueOf(number.longValue());
            }
        }
        if (type == Boolean.class) {
            if (value instanceof Number number) {
                return number.intValue() != 0;
            }
            return Boolean.valueOf(String.valueOf(value));
        }
        if (type.isEnum()) {
            try {
                return parseEnum(type, String.valueOf(value));
            } catch (CheckerException e) {
                throw new ModelDefinitionException(
                        "列值「" + value + "」无法映射到枚举 " + type.getSimpleName(), e);
            }
        }
        if (type == LocalDateTime.class) {
            if (value instanceof java.sql.Timestamp timestamp) {
                return timestamp.toLocalDateTime();
            }
            if (value instanceof Date date) {
                return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
            }
            return parseQuietly(value, ValueConverter::parseDateTime);
        }
        if (type == LocalDate.class) {
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate();
            }
            if (value instanceof java.sql.Timestamp timestamp) {
                return timestamp.toLocalDateTime().toLocalDate();
            }
            if (value instanceof java.util.Date date) {
                return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            return parseQuietly(value, ValueConverter::parseDate);
        }
        if (type == Date.class) {
            if (value instanceof java.sql.Timestamp timestamp) {
                return new Date(timestamp.getTime());
            }
            if (value instanceof java.sql.Date date) {
                return new Date(date.getTime());
            }
            if (value instanceof LocalDateTime dateTime) {
                return toDate(dateTime);
            }
            if (value instanceof LocalDate date) {
                return toDate(date.atStartOfDay());
            }
            return null;
        }
        if (type == Instant.class) {
            if (value instanceof Date date) {
                return date.toInstant();
            }
            if (value instanceof LocalDateTime dateTime) {
                return dateTime.atZone(ZoneId.systemDefault()).toInstant();
            }
            return null;
        }
        return value;
    }

    private interface Parser<T> {
        T parse(String value) throws CheckerException;
    }

    private static Object parseQuietly(Object value, Parser<?> parser) {
        try {
            return parser.parse(String.valueOf(value));
        } catch (CheckerException e) {
            return null;
        }
    }

    /**
     * 把值转换成适合放进 HTML 属性或文本的字符串。
     *
     * @param value 值
     * @return 字符串
     */
    public static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date date) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .format(LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
        }
        if (value instanceof LocalDate date) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(date);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return String.valueOf(value);
    }

    /**
     * 把值转换成用于数据库写入的形态（枚举写常量名、日期写 {@link Date}）。
     *
     * @param value 值
     * @return 可写入 JDBC 参数的值
     */
    public static Object toDatabase(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof LocalDate date) {
            return java.sql.Date.valueOf(date);
        }
        if (value instanceof LocalDateTime dateTime) {
            return java.sql.Timestamp.valueOf(dateTime);
        }
        if (value instanceof Instant instant) {
            return java.sql.Timestamp.from(instant);
        }
        return value;
    }

    /**
     * 空值安全判断。
     *
     * @param value 值
     * @return 为空返回 {@code true}
     */
    public static boolean isBlank(Object value) {
        return value == null
               || (value instanceof String string && string.isBlank())
               || (value instanceof CharSequence sequence && sequence.toString().isBlank());
    }

    /**
     * 按字段名读取属性值，用于模板渲染——它拿到的往往是 Map 里的值或任意 DTO，
     * 不一定带 {@code @ModelField}。
     * <p>
     * 取值顺序：无参方法 {@code name()} → getter {@code getName()} → 同名字段（含父类）。
     * 找不到或读失败时返回 {@code null}，让模板显示为空而不是抛异常。
     *
     * @param bean  对象
     * @param field 字段名
     * @return 值，取不到时为 {@code null}
     */
    public static Object read(Object bean, String field) {
        if (bean == null || field == null || field.isEmpty()) {
            return null;
        }
        if (bean instanceof Map<?, ?> map) {
            return map.get(field);
        }
        Accessor accessor = ACCESSORS.computeIfAbsent(bean.getClass(), ValueConverter::indexAccessors).get(field);
        return accessor == null ? null : accessor.read(bean);
    }

    @FunctionalInterface
    private interface Accessor {
        Object read(Object bean);
    }

    private static final Map<Class<?>, Map<String, Accessor>> ACCESSORS = new ConcurrentHashMap<>();

    private static Map<String, Accessor> indexAccessors(Class<?> type) {
        Map<String, Accessor> index = new ConcurrentHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                index.putIfAbsent(field.getName(), bean -> {
                    try {
                        return field.get(bean);
                    } catch (IllegalAccessException e) {
                        return null;
                    }
                });
            }
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                    || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    continue;
                }
                method.setAccessible(true);
                Accessor accessor = bean -> {
                    try {
                        return method.invoke(bean);
                    } catch (ReflectiveOperationException e) {
                        return null;
                    }
                };
                String name = method.getName();
                index.putIfAbsent(name, accessor);
                if (name.startsWith("get") && name.length() > 3) {
                    index.putIfAbsent(decapitalize(name.substring(3)), accessor);
                } else if (name.startsWith("is") && name.length() > 2
                           && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                    index.putIfAbsent(decapitalize(name.substring(2)), accessor);
                }
            }
        }
        return index;
    }

    private static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
