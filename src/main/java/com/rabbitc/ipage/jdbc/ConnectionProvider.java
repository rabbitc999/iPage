package com.rabbitc.ipage.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 连接来源。
 * <p>
 * 框架不绑定任何连接池：{@link DataSource} 由宿主提供，{@code pool} 直接取用。
 * 如果宿主用的是 Spring 的 {@code DataSourceUtils}（同一事务内复用连接），
 * 实现本接口并用 {@code DataSourceUtils.getConnection/releaseConnection} 代理即可，
 * 框架侧无需感知事务语义。
 * <p>
 * 反过来也成立：不想引入连接池时，用 {@link #of(String, String, String)} 直接给 URL，
 * 框架每个方法调用借一次 {@link DriverManager} 连接，用完立刻 close。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * 借出一个连接。
     *
     * @return 连接
     * @throws SQLException 无法获取连接
     */
    Connection getConnection() throws SQLException;

    /**
     * 用数据源构造连接来源，每次调用取一个新连接（由数据源决定是否池化）。
     *
     * @param dataSource 数据源
     * @return 连接来源
     */
    static ConnectionProvider of(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("数据源不能为空");
        }
        return dataSource::getConnection;
    }

    /**
     * 直接按 JDBC URL 构造连接来源，不依赖连接池，也不依赖 javax.sql 之外的东西。
     *
     * @param url      JDBC URL，例如 {@code jdbc:postgresql://127.0.0.1:5432/school}
     * @param user     用户名，可为 {@code null}
     * @param password 密码，可为 {@code null}
     * @return 连接来源
     */
    static ConnectionProvider of(String url, String user, String password) {
        return of(url, properties(user, password));
    }

    /**
     * 直接按 JDBC URL 与连接属性构造连接来源。
     * <p>
     * 属性会原样传给驱动，{@code user} / {@code password} 从属性里读，
     * 其余键（{@code ssl}、{@code connectTimeout}、{@code currentSchema} 等）由驱动解释。
     *
     * @param url         JDBC URL
     * @param properties  连接属性
     * @return 连接来源
     */
    static ConnectionProvider of(String url, Properties properties) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("JDBC URL 不能为空");
        }
        Properties copy = new Properties();
        if (properties != null) {
            copy.putAll(properties);
        }
        return () -> DriverManager.getConnection(url, copy);
    }

    /**
     * 组装 user / password 属性，空值不写入（免得驱动收到空字符串用户名）。
     *
     * @param user     用户名
     * @param password 密码
     * @return 属性
     */
    private static Properties properties(String user, String password) {
        Properties properties = new Properties();
        if (user != null && !user.isBlank()) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return properties;
    }
}
