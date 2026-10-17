package com.rabbitc.ipage.exception;

/**
 * 模型定义错误：缺少注解、列名重复、关联关系无法解析等。
 * <p>
 * 这类错误都在启动期或首次访问时暴露，属于开发期的配置错误，不做静默兜底。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public class ModelDefinitionException extends RuntimeException {

    /**
     * 构造函数。
     *
     * @param message 错误信息
     */
    public ModelDefinitionException(String message) {
        super(message);
    }

    /**
     * 构造函数。
     *
     * @param message 错误信息
     * @param cause   原因
     */
    public ModelDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
