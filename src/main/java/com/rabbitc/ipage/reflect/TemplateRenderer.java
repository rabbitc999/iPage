package com.rabbitc.ipage.reflect;

import com.rabbitc.ipage.exception.ModelDefinitionException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量模板渲染器，支持 {@code ${path.to.field}} 占位符。
 * <p>
 * 路径每一段依次尝试：字段、{@code getXxx()}、{@code isXxx()}、记录组件、Map 键。
 * 解析失败时输出空串而不是抛异常——这是显示层，宁可少显示也不能让页面 500。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Map<Class<?>, Map<String, Member>> MEMBER_CACHE = new ConcurrentHashMap<>();

    private record Member(Field field, Method getter) {
    }

    private TemplateRenderer() {
    }

    /**
     * 按模板渲染对象。
     *
     * @param root     根对象
     * @param template 模板文本
     * @return 渲染结果
     */
    public static String render(Object root, String template) {
        if (template == null || template.isEmpty()) {
            return root == null ? "" : String.valueOf(root);
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        Map<String, String> cache = new LinkedHashMap<>();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String replacement = cache.computeIfAbsent(path, key -> {
                Object value = resolve(root, key);
                return value == null ? "" : String.valueOf(value);
            });
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 按路径取值。
     *
     * @param root 根对象
     * @param path 以 {@code .} 分隔的路径
     * @return 值，无法解析时为 {@code null}
     */
    public static Object resolve(Object root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || segment.isBlank()) {
                return null;
            }
            current = access(current, segment.trim());
        }
        return current;
    }

    private static Object access(Object target, String name) {
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        Member member = MEMBER_CACHE
                .computeIfAbsent(target.getClass(), TemplateRenderer::indexMembers)
                .get(name);
        if (member == null) {
            return null;
        }
        try {
            if (member.field() != null) {
                return member.field().get(target);
            }
            return member.getter().invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Map<String, Member> indexMembers(Class<?> type) {
        Map<String, Member> index = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                index.putIfAbsent(field.getName(), new Member(field, null));
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    continue;
                }
                String name = method.getName();
                String property = null;
                if (name.startsWith("get") && name.length() > 3) {
                    property = decapitalize(name.substring(3));
                } else if (name.startsWith("is") && name.length() > 2
                           && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                    property = decapitalize(name.substring(2));
                }
                if (property != null) {
                    method.setAccessible(true);
                    index.putIfAbsent(property, new Member(null, method));
                }
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    index.putIfAbsent(component.getName(), new Member(null, accessor));
                } catch (IllegalArgumentException e) {
                    throw new ModelDefinitionException("记录组件的访问器不可用：" + component.getName(), e);
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
