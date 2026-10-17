package com.rabbitc.ipage.exception;

/**
 * 校验失败异常。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public class CheckerException extends Exception {

    /**
     * 构造函数。
     *
     * @param message 校验失败信息
     */
    public CheckerException(String message) {
        super(message == null || message.isBlank() ? "数据校验失败" : message);
    }

    /**
     * 构造函数。
     *
     * @param message 校验失败信息
     * @param cause   原因
     */
    public CheckerException(String message, Throwable cause) {
        super(message, cause);
    }
}
