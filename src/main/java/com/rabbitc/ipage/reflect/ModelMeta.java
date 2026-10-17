package com.rabbitc.ipage.reflect;

import com.rabbitc.ipage.annotation.ModelClass;
import com.rabbitc.ipage.annotation.ModelEnum;
import com.rabbitc.ipage.annotation.ModelField;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.OperatorType;
import com.rabbitc.ipage.enums.SortType;
import com.rabbitc.ipage.exception.ModelDefinitionException;
import com.rabbitc.ipage.meta.ModelPermission;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型元数据：一个 {@link ModelClass} 模型类的全部结构信息。
 * <p>
 * 与旧实现的不同：
 * <ul>
 *     <li>反射只在首次解析时发生一次，之后全部走缓存的 {@link FieldDescriptor}；</li>
 *     <li>主键、外键、可搜索列等派生信息在解析期算好，不再每次查询重新扫描；</li>
 *     <li>模型定义错误（缺列名、重复列名、找不到主键）在解析期直接抛出，而不是运行到一半才炸。</li>
 * </ul>
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class ModelMeta {

    private static final Map<Class<?>, ModelMeta> CACHE = new ConcurrentHashMap<>();

    /**
     * 表名被改写过的元数据，按「类 + 新表名」缓存。
     */
    private static final Map<RenameKey, ModelMeta> RENAMED = new ConcurrentHashMap<>();

    private final Class<?> modelClass;
    private final String name;
    private final String table;
    private final String icon;
    private final String displayTemplate;
    private final ModelPermission lookPermission;
    private final ModelPermission editPermission;
    private final ModelPermission deletePermission;
    private final List<FieldDescriptor> fields;
    private final List<FieldDescriptor> columnFields;
    private final List<FieldDescriptor> aggregateFields;
    private final FieldDescriptor idField;
    private final Map<String, FieldDescriptor> byName;
    private final Map<String, FieldDescriptor> byColumn;

    private ModelMeta(Class<?> modelClass) {
        this(modelClass, null);
    }

    /**
     * 解析模型。
     *
     * @param modelClass 模型类
     * @param tableName  表名覆盖值，{@code null} 时用注解里的表名
     */
    private ModelMeta(Class<?> modelClass, String tableName) {
        this.modelClass = modelClass;
        ModelClass annotation = modelClass.getAnnotation(ModelClass.class);
        if (annotation == null) {
            throw new ModelDefinitionException(
                    "类 " + modelClass.getName() + " 缺少 @ModelClass 注解，不能作为 ipage 模型");
        }
        this.name = annotation.name();
        this.table = tableName != null
                ? tableName
                : (annotation.table() == null || annotation.table().isBlank()
                   ? modelClass.getSimpleName() : annotation.table().trim());
        this.icon = annotation.icon();
        this.displayTemplate = annotation.text();
        this.lookPermission = annotation.look();
        this.editPermission = annotation.edit();
        this.deletePermission = annotation.delete();

        List<FieldDescriptor> collected = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();
        Map<String, FieldDescriptor> nameIndex = new LinkedHashMap<>();
        Map<String, FieldDescriptor> columnIndex = new LinkedHashMap<>();

        for (Class<?> current = modelClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                ModelField fieldAnnotation = field.getAnnotation(ModelField.class);
                if (fieldAnnotation == null) {
                    continue;
                }
                FieldDescriptor descriptor = new FieldDescriptor(field, fieldAnnotation, modelClass,
                        lookPermission, editPermission, collectValidators(field));
                validate(descriptor);
                if (nameIndex.putIfAbsent(descriptor.name(), descriptor) != null) {
                    throw new ModelDefinitionException(
                            "模型 " + modelClass.getSimpleName() + " 的字段 " + descriptor.name() + " 在继承层次上重复声明");
                }
                if (descriptor.isColumn()) {
                    if (!columns.add(descriptor.column())) {
                        throw new ModelDefinitionException(
                                "模型 " + modelClass.getSimpleName() + " 的列名 " + descriptor.column()
                                + " 被多个字段占用（字段 " + descriptor.name() + "）");
                    }
                    columnIndex.put(descriptor.column(), descriptor);
                }
                collected.add(descriptor);
            }
        }

        collected.sort(Comparator.comparingInt(FieldDescriptor::order));
        this.fields = Collections.unmodifiableList(collected);
        this.byName = Collections.unmodifiableMap(nameIndex);
        this.byColumn = Collections.unmodifiableMap(columnIndex);

        List<FieldDescriptor> columnsOnly = new ArrayList<>();
        List<FieldDescriptor> aggregates = new ArrayList<>();
        FieldDescriptor id = null;
        for (FieldDescriptor descriptor : fields) {
            if (descriptor.type() == FieldType.MODEL_ID && id == null) {
                id = descriptor;
            }
            if (descriptor.isColumn()) {
                columnsOnly.add(descriptor);
            }
            if (descriptor.type() == FieldType.AGGREGATION) {
                aggregates.add(descriptor);
            }
        }
        if (id == null) {
            throw new ModelDefinitionException(
                    "模型 " + modelClass.getSimpleName() + " 没有 type = FieldType.MODEL_ID 的主键字段");
        }
        this.idField = id;
        this.columnFields = Collections.unmodifiableList(columnsOnly);
        this.aggregateFields = Collections.unmodifiableList(aggregates);
    }

    private static void validate(FieldDescriptor descriptor) {
        String where = descriptor.ownersClass().getSimpleName() + "." + descriptor.name();
        if (descriptor.column().isBlank()) {
            throw new ModelDefinitionException("字段 " + where + " 的 @ModelField.column 不允许为空");
        }
        switch (descriptor.type()) {
            case ENUM -> {
                if (!descriptor.javaType().isEnum()) {
                    throw new ModelDefinitionException(
                            "字段 " + where + " 声明为 ENUM，但 Java 类型不是枚举");
                }
            }
            case ASSOCIATION_ID, ASSOCIATION_MODEL, AGGREGATION -> {
                if (descriptor.relatedClass() == null) {
                    throw new ModelDefinitionException(
                            "字段 " + where + " 是 " + descriptor.type() + "，必须通过 byModel 指定关联模型");
                }
                if (descriptor.relatedClass().getAnnotation(ModelClass.class) == null) {
                    throw new ModelDefinitionException(
                            "字段 " + where + " 关联的类 " + descriptor.relatedClass().getName()
                            + " 不是 ipage 模型（缺少 @ModelClass）");
                }
            }
            default -> {
            }
        }
        if (descriptor.type() == FieldType.ASSOCIATION_MODEL
            && descriptor.relatedClass() != null
            && descriptor.javaType() != descriptor.relatedClass()) {
            throw new ModelDefinitionException(
                    "字段 " + where + " 的 Java 类型 " + descriptor.javaType().getSimpleName()
                    + " 与 byModel 指定的 " + descriptor.relatedClass().getSimpleName() + " 不一致");
        }
    }

    private static List<java.lang.annotation.Annotation> collectValidators(Field field) {
        List<java.lang.annotation.Annotation> list = new ArrayList<>(3);
        for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
            String pkg = annotation.annotationType().getPackageName();
            if ("com.rabbitc.ipage.annotation".equals(pkg)
                && annotation.annotationType().getSimpleName().startsWith("Validator")) {
                list.add(annotation);
            }
        }
        return list;
    }

    /**
     * 取模型元数据，首次调用时解析并缓存。
     *
     * @param clazz 模型类
     * @return 元数据
     * @throws ModelDefinitionException 模型定义不合法
     */
    public static ModelMeta of(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, ModelMeta::new);
    }

    /**
     * 取一份把表名改写过的元数据。
     * <p>
     * 表名前缀、分库后的物理表名这类「同一份模型、落到不同表」的场景，靠 {@code tableMapper}
     * 在解析后改写表名即可；元数据按 {@code 类 + 改写后的表名} 缓存，
     * 因此 {@code dev_student} 与 {@code test_student} 各有一份，互不覆盖。
     * <p>
     * 改写只影响得到的这一份元数据，全局缓存里注解原本的那份不受影响。
     *
     * @param tableMapper 表名映射器，输入注解里的表名，输出实际表名
     * @param clazz       模型类
     * @return 元数据
     */
    public static ModelMeta of(java.util.function.UnaryOperator<String> tableMapper, Class<?> clazz) {
        ModelMeta base = of(clazz);
        if (tableMapper == null) {
            return base;
        }
        String mapped = tableMapper.apply(base.table);
        if (mapped == null || mapped.isBlank() || mapped.equals(base.table)) {
            return base;
        }
        return RENAMED.computeIfAbsent(new RenameKey(clazz, mapped),
                key -> new ModelMeta(key.modelClass(), key.table()));
    }

    /**
     * 改写后的元数据缓存键。
     *
     * @param modelClass 模型类
     * @param table      改写后的表名
     */
    private record RenameKey(Class<?> modelClass, String table) {
    }

    /**
     * 判断某个类是否是 ipage 模型。
     *
     * @param clazz 类
     * @return 是模型返回 {@code true}
     */
    public static boolean isModel(Class<?> clazz) {
        return clazz != null && clazz.isAnnotationPresent(ModelClass.class);
    }

    /**
     * 清空缓存，仅供测试或热重载使用。
     */
    public static void clearCache() {
        CACHE.clear();
        RENAMED.clear();
    }

    /**
     * 模型类。
     *
     * @return 模型类
     */
    public Class<?> modelClass() {
        return modelClass;
    }

    /**
     * 模型名称。
     *
     * @return 名称
     */
    public String name() {
        return name;
    }

    /**
     * 表名。
     *
     * @return 表名
     */
    public String table() {
        return table;
    }

    /**
     * 菜单图标。
     *
     * @return 图标类名
     */
    public String icon() {
        return icon;
    }

    /**
     * 显示模板。
     *
     * @return 模板，未配置时为空串
     */
    public String displayTemplate() {
        return displayTemplate;
    }

    /**
     * 查看权限。
     *
     * @return 查看权限
     */
    public ModelPermission lookPermission() {
        return lookPermission;
    }

    /**
     * 编辑权限。
     *
     * @return 编辑权限
     */
    public ModelPermission editPermission() {
        return editPermission;
    }

    /**
     * 删除权限。
     *
     * @return 删除权限
     */
    public ModelPermission deletePermission() {
        return deletePermission;
    }

    /**
     * 全部字段，按 order 升序。
     *
     * @return 字段列表
     */
    public List<FieldDescriptor> fields() {
        return fields;
    }

    /**
     * 与表格列一一对应的字段（不含关联对象与聚合值）。
     *
     * @return 字段列表
     */
    public List<FieldDescriptor> columnFields() {
        return columnFields;
    }

    /**
     * 聚合字段。
     *
     * @return 字段列表
     */
    public List<FieldDescriptor> aggregateFields() {
        return aggregateFields;
    }

    /**
     * 主键字段。
     *
     * @return 主键字段
     */
    public FieldDescriptor idField() {
        return idField;
    }

    /**
     * 主键列名。
     *
     * @return 列名
     */
    public String idColumn() {
        return idField.column();
    }

    /**
     * 主键上的显示文本。
     * <p>
     * 页面读的是 {@code model.value(entity, idField)}，这里统一走显示模板，
     * 免得同一行在「列表」「详情」「下拉框」三处出现三种写法。
     *
     * @param entity 实体
     * @return 显示文本，实体为空时为空串
     */
    public String idText(Object entity) {
        return display(entity);
    }

    /**
     * 按 Java 字段名查找。
     *
     * @param fieldName 字段名
     * @return 字段描述符，不存在时为空
     */
    public Optional<FieldDescriptor> field(String fieldName) {
        return Optional.ofNullable(byName.get(fieldName));
    }

    /**
     * 按数据库列名查找。
     *
     * @param column 列名
     * @return 字段描述符，不存在时为空
     */
    public Optional<FieldDescriptor> fieldByColumn(String column) {
        return Optional.ofNullable(byColumn.get(column));
    }

    /**
     * 按 Java 字段名或列名查找，两者都认。
     *
     * @param nameOrColumn 字段名或列名
     * @return 字段描述符，不存在时为空
     */
    public Optional<FieldDescriptor> resolve(String nameOrColumn) {
        FieldDescriptor direct = byName.get(nameOrColumn);
        return Optional.ofNullable(direct != null ? direct : byColumn.get(nameOrColumn));
    }

    /**
     * 主键值。
     *
     * @param entity 实体对象
     * @return 主键值，可能为 {@code null}
     */
    public Object idValue(Object entity) {
        if (entity == null) {
            return null;
        }
        Object value = idField.read(entity);
        return value instanceof Number number ? number : value;
    }

    /**
     * 主键的数值形式，非数值主键返回 {@code null}。
     *
     * @param entity 实体对象
     * @return 主键数值
     */
    public Integer idValueAsInt(Object entity) {
        Object value = idValue(entity);
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * 解析主键值的 Java 类型。
     *
     * @return 主键 Java 类型
     */
    public Class<?> idJavaType() {
        return idField.javaType();
    }

    /**
     * 生成空实体。
     *
     * @return 新实例
     */
    public Object newInstance() {
        try {
            var constructor = modelClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ModelDefinitionException(
                    "模型 " + modelClass.getName() + " 无法实例化，请提供无参构造方法", e);
        }
    }

    /**
     * 按显示模板或 {@code toString()} 渲染实体的显示文本。
     *
     * @param entity 实体
     * @return 显示文本
     */
    public String display(Object entity) {
        if (entity == null) {
            return "";
        }
        if (!displayTemplate.isBlank()) {
            return TemplateRenderer.render(entity, displayTemplate);
        }
        return String.valueOf(entity);
    }

    /**
     * 某个字段的枚举选项，用于下拉框。
     *
     * @param field 枚举字段
     * @return 选项列表，键为常量名，值为显示文本
     */
    public static Map<String, String> enumOptions(FieldDescriptor field) {
        if (field.enumClass() == null) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (Object constant : field.enumClass().getEnumConstants()) {
            String constantName = ((Enum<?>) constant).name();
            String text = constantName;
            for (Field declared : field.enumClass().getDeclaredFields()) {
                if (!declared.getName().equals(constantName)) {
                    continue;
                }
                ModelEnum modelEnum = declared.getAnnotation(ModelEnum.class);
                if (modelEnum != null) {
                    if (modelEnum.hide()) {
                        text = null;
                        break;
                    }
                    text = modelEnum.text();
                }
            }
            if (text != null) {
                options.put(constantName, text);
            }
        }
        return options;
    }

    /**
     * 某个字段允许的查询操作符。
     *
     * @param field 字段
     * @return 操作符列表
     */
    public static List<OperatorType> allowedOperators(FieldDescriptor field) {
        return switch (field.type()) {
            case NUMBER, DATE -> List.of(OperatorType.EQ, OperatorType.GT, OperatorType.LT,
                    OperatorType.GTE, OperatorType.LTE, OperatorType.NE);
            case MODEL_ID, ASSOCIATION_ID -> List.of(OperatorType.EQ, OperatorType.NE);
            default -> List.of(OperatorType.EQ, OperatorType.LIKE, OperatorType.STARTS_WITH,
                    OperatorType.ENDS_WITH, OperatorType.IS_NULL, OperatorType.IS_NOT_NULL);
        };
    }

    /**
     * 该模型支持的排序字段。
     *
     * @return 字段名集合
     */
    public Collection<String> sortableFields() {
        return fields.stream()
                .filter(field -> field.isColumn() || field.type() == FieldType.AGGREGATION)
                .map(FieldDescriptor::name)
                .toList();
    }

    /**
     * 默认排序方向。
     *
     * @return 默认排序
     */
    public SortType defaultSort() {
        return SortType.ASC;
    }

    @Override
    public String toString() {
        return "ModelMeta[" + modelClass.getSimpleName() + " -> " + table + "]";
    }

}
