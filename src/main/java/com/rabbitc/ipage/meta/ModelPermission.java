package com.rabbitc.ipage.meta;

/**
 * 权限等级，取值越大权限越高。
 * <p>
 * 框架只认「等级」这一个数字，不关心等级从哪来：宿主应用可以把用户表里的权限枚举
 * 映射成任意等级（{@link #level()} 给数字），也可以直接用内置等级。
 * 内置等级覆盖常见的教学系统分层：
 * <ul>
 *     <li>{@link #LEAST}：所有人都满足，用作「不限制」；</li>
 *     <li>{@link #STUDENT} / {@link #TEACHER} / {@link #ADMIN}：常见的教学系统分层；</li>
 *     <li>{@link #SUPER_ADMIN}：最高等级；</li>
 *     <li>{@link #FORBIDDEN}：谁都不满足，用作「禁用」。</li>
 * </ul>
 * 做成枚举是为了能出现在注解的默认值里。宿主若需要别的等级，不要扩展本枚举，
 * 而是把 {@code of(level)} 的判定改为按 {@link #level()} 比较——{@link #granted(int)}
 * 已经是这样做的，因此字段级权限既可以写枚举常量，也可以由代码按数字授予。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public enum ModelPermission {

    /**
     * 不限制。
     */
    LEAST(Integer.MIN_VALUE),

    /**
     * 继承模型类上的设置，仅用于字段注解的默认值。
     */
    INHERIT(Integer.MIN_VALUE + 1),

    /**
     * 学生。
     */
    STUDENT(0),

    /**
     * 普通用户。
     */
    NORMAL(1),

    /**
     * 教师。
     */
    TEACHER(2),

    /**
     * 管理员。
     */
    ADMIN(3),

    /**
     * 超级管理员。
     */
    SUPER_ADMIN(4),

    /**
     * 禁用，谁都不满足。
     */
    FORBIDDEN(Integer.MAX_VALUE);

    private final int level;

    ModelPermission(int level) {
        this.level = level;
    }

    /**
     * 按等级值取内置权限。
     *
     * @param level 等级
     * @return 对应权限
     * @throws IllegalArgumentException 等级不在内置范围内
     */
    public static ModelPermission of(int level) {
        for (ModelPermission value : values()) {
            if (value.level == level) {
                return value;
            }
        }
        throw new IllegalArgumentException("没有内置等级为 " + level + " 的权限，请直接比较等级数字");
    }

    /**
     * 等级值，用于和用户等级比较。
     *
     * @return 等级
     */
    public int level() {
        return level;
    }

    /**
     * 判断给定等级是否满足本权限。
     *
     * @param userLevel 用户等级
     * @return 满足返回 {@code true}
     */
    public boolean granted(int userLevel) {
        return userLevel >= level;
    }
}
