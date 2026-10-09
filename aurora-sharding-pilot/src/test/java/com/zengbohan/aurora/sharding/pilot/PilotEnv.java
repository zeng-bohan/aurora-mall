package com.zengbohan.aurora.sharding.pilot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 试点装置：
 * <ul>
 *   <li>{@code sharded} —— 走 ShardingSphere 的逻辑连接（被测对象）；</li>
 *   <li>{@code raw0}/{@code raw1} —— 直连两个物理库（造夹具与"取证"：证明数据真的落在哪个库）；</li>
 *   <li>{@code captureSql} —— 抓 ShardingSphere 打印的 Actual SQL，用来断言路由到哪一片。</li>
 * </ul>
 * 缺 MySQL 时调用方 assume 跳过（与库存/秒杀的 Redis Lua 测试同法）。
 */
final class PilotEnv {

    private static final String USER = "root";
    private static final String PASSWORD = "aurora123";
    private static final String PARAMS =
            "?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    static final String URL_DS0 = "jdbc:mysql://localhost:13306/aurora_order_shard0" + PARAMS;
    static final String URL_DS1 = "jdbc:mysql://localhost:13306/aurora_order_shard1" + PARAMS;

    static DataSource raw0;
    static DataSource raw1;
    static DataSource sharded;

    private PilotEnv() {
    }

    static boolean mysqlReachable() {
        try (Connection ignored = DriverManager.getConnection(URL_DS0, USER, PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    static synchronized void boot() throws Exception {
        if (sharded != null) {
            return;
        }
        raw0 = pool(URL_DS0);
        raw1 = pool(URL_DS1);
        sharded = YamlShardingSphereDataSourceFactory.createDataSource(
                new File("src/test/resources/sharding-pilot.yaml"));
    }

    static synchronized void close() {
        // 注意：MySQL 不可达时 boot() 会被 assume 跳过，raw0/raw1 仍是 null。
        // 这里必须容忍"从未启动"的状态——List.of(null) 会抛 NPE，那样被跳过的用例
        // 会在 @AfterAll 里变成 error（CI 上就是这么红的）。
        for (DataSource raw : new DataSource[]{raw0, raw1}) {
            if (raw instanceof HikariDataSource hikari) {
                hikari.close();
            }
        }
        raw0 = null;
        raw1 = null;
        sharded = null;
    }

    private static DataSource pool(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(USER);
        config.setPassword(PASSWORD);
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    static void reset() throws SQLException {
        for (DataSource raw : List.of(raw0, raw1)) {
            try (Connection connection = raw.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE orders");
                statement.execute("TRUNCATE TABLE pilot_autoinc");
                statement.execute("TRUNCATE TABLE pilot_unique");
            }
        }
    }

    static int exec(DataSource dataSource, String sql, Object... args) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return exec(connection, sql, args);
        }
    }

    /** 事务用例要自己在同一个连接上发多条语句，所以连接版单独给一个。 */
    static int exec(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdate();
        }
    }

    static void insertOrder(Connection connection, long id, long userId, long skuId, String amount)
            throws SQLException {
        exec(connection, "INSERT INTO orders (id, user_id, sku_id, quantity, total_amount, status) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, userId, skuId, 1, new BigDecimal(amount), 0);
    }

    static void insertOrder(DataSource dataSource, long id, long userId, String amount) throws SQLException {
        exec(dataSource, "INSERT INTO orders (id, user_id, sku_id, quantity, total_amount, status) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, userId, 7L, 1, new BigDecimal(amount), 0);
    }

    static List<Long> longs(DataSource dataSource, String sql, Object... args) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getLong(1));
                }
            }
        }
        return values;
    }

    static BigDecimal decimal(DataSource dataSource, String sql, Object... args) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    // ---- Actual SQL 抓取 --------------------------------------------------

    static ListAppender<ILoggingEvent> captureSql() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("ShardingSphere-SQL")).addAppender(appender);
        return appender;
    }

    static void stopCapture(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger("ShardingSphere-SQL")).detachAppender(appender);
    }

    static List<String> actualSql(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Actual SQL"))
                .toList();
    }

    /** 断言这次执行只打到给定的这几片（其余分片不得出现 Actual SQL）。 */
    static void assertRoutedTo(List<String> actualSql, String... shards) {
        assertThat(actualSql).as("应有 Actual SQL 记录（sql-show: true）").isNotEmpty();
        for (String shard : shards) {
            assertThat(actualSql)
                    .as("应对 %s 执行", shard)
                    .anySatisfy(sql -> assertThat(sql).contains(shard + " :::"));
        }
        for (String shard : List.of("ds0", "ds1")) {
            if (!List.of(shards).contains(shard)) {
                assertThat(actualSql)
                        .as("不应打到 %s", shard)
                        .noneSatisfy(sql -> assertThat(sql).contains(shard + " :::"));
            }
        }
    }
}
