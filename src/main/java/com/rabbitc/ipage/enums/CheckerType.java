package com.rabbitc.ipage.enums;

/**
 * 模型操作的种类。
 * <p>
 * 既用于校验时区分场景，也用于控制器里区分「增 / 改 / 删」。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public enum CheckerType {
    /**
     * 新增。
     */
    ADD,
    /**
     * 修改。
     */
    UPDATE,
    /**
     * 删除。
     */
    DELETE
}
