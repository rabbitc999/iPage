package com.rabbitc.ipage.reflect;

import com.rabbitc.ipage.annotation.ValidatorLength;
import com.rabbitc.ipage.annotation.ValidatorModel;
import com.rabbitc.ipage.annotation.ValidatorNotNull;
import com.rabbitc.ipage.annotation.ValidatorPattern;
import com.rabbitc.ipage.annotation.ValidatorRange;
import com.rabbitc.ipage.enums.CheckerType;
import com.rabbitc.ipage.exception.CheckerException;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collection;

/**
 * 基于注解的校验器。
 * <p>
 * 校验流程分三步：
 * <ol>
 *     <li>逐字段执行校验注解（非空、长度、正则、范围、嵌套模型）；</li>
 *     <li>若模型类声明了 {@code public String checker(self, before, CheckerType)}，调用它——
 *         这是需要读库的跨字段规则（例如「用户名不能重复」）的挂载点，
 *         {@code before} 是修改前的快照，新增时为 {@code null}；</li>
 *     <li>若模型类声明了 {@code public String process(CheckerType)}，再调用它做不依赖旧值的规则。</li>
 * </ol>
 * 两种自定义方法返回非空字符串即视为校验失败，异常一律包装成 {@link CheckerException}。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class ModelValidator {

    private ModelValidator() {
    }

    /**
     * 校验一个模型实例。
     *
     * @param entity    待校验实体
     * @param operation 操作类型
     * @param before    修改前的实体快照，新增时为 {@code null}
     * @throws CheckerException 校验失败
     */
    public static void validate(Object entity, CheckerType operation, Object before) throws CheckerException {
        if (entity == null) {
            throw new CheckerException("待校验的模型为空");
        }
        ModelMeta meta = ModelMeta.of(entity.getClass());
        validateFields(meta, entity, operation);
        invokeCustomChecker(entity, before, operation);
        invokeProcess(entity, operation);
    }

    private static void validateFields(ModelMeta meta, Object entity, CheckerType operation) throws CheckerException {
        for (FieldDescriptor field : meta.fields()) {
            if (field.validators().isEmpty()) {
                continue;
            }
            Object value = field.read(entity);
            for (Annotation validator : field.validators()) {
                if (validator instanceof ValidatorNotNull notNull) {
                    if (value == null || (value instanceof String string && string.isBlank())) {
                        throw new CheckerException(text(notNull.message(), field.text() + "不能为空"));
                    }
                } else if (validator instanceof ValidatorLength length) {
                    if (value instanceof String string
                        && (string.length() < length.min() || string.length() > length.max())) {
                        throw new CheckerException(text(length.message(),
                                "%s长度必须在 %d 到 %d 之间".formatted(field.text(), length.min(), length.max())));
                    }
                } else if (validator instanceof ValidatorPattern pattern) {
                    if (value instanceof String string) {
                        boolean match;
                        try {
                            match = string.matches(pattern.regexp());
                        } catch (RuntimeException e) {
                            throw new CheckerException("字段 " + field.text() + " 的正则表达式非法", e);
                        }
                        if (!match) {
                            throw new CheckerException(text(pattern.message(), field.text() + "格式不正确"));
                        }
                    }
                } else if (validator instanceof ValidatorRange range) {
                    if (value instanceof Number number) {
                        BigDecimal decimal = new BigDecimal(number.toString());
                        if (decimal.compareTo(BigDecimal.valueOf(range.min())) < 0
                            || decimal.compareTo(BigDecimal.valueOf(range.max())) > 0) {
                            throw new CheckerException(text(range.message(),
                                    "%s必须在 %d 到 %d 之间".formatted(field.text(), range.min(), range.max())));
                        }
                    }
                } else if (validator instanceof ValidatorModel) {
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Collection<?> collection) {
                        for (Object item : collection) {
                            if (item != null) {
                                validate(item, operation, null);
                            }
                        }
                    } else {
                        validate(value, operation, null);
                    }
                }
                // 非框架注解一律忽略
            }
        }
    }

    private static void invokeCustomChecker(Object entity, Object before, CheckerType operation)
            throws CheckerException {
        Method method = findMethod(entity.getClass(), "checker", entity.getClass(), CheckerType.class);
        if (method == null) {
            return;
        }
        Object result = invoke(method, entity, before, operation);
        String message = result == null ? "" : result.toString();
        if (!message.isEmpty()) {
            throw new CheckerException(message);
        }
    }

    private static void invokeProcess(Object entity, CheckerType operation) throws CheckerException {
        Method method = findMethod(entity.getClass(), "process", CheckerType.class);
        if (method == null) {
            return;
        }
        Object result = invoke(method, entity, operation);
        String message = result == null ? "" : result.toString();
        if (!message.isEmpty()) {
            throw new CheckerException(message);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method method, Object entity, Object... args) throws CheckerException {
        try {
            return method.invoke(entity, args);
        } catch (IllegalAccessException e) {
            throw new CheckerException("无法调用校验方法 " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CheckerException checkerException) {
                throw checkerException;
            }
            throw new CheckerException("校验方法 " + method.getName() + " 执行失败："
                                       + (cause == null ? "" : cause.getMessage()), cause);
        }
    }

    private static String text(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
