package com.acme.platform.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 连接池下的租户上下文验证（WP01-03）。
 *
 * <p>RLS 策略依赖 {@code app.tenant_id} 这个会话变量。连接池会把同一个物理连接反复借出，
 * 所以「上下文什么时候失效」决定了会不会串租户。这个用例在真实 PostgreSQL 上锁死三件事：
 *
 * <ol>
 *   <li>事务级 {@code SET LOCAL} 提交后立即失效，同一条连接的下一次借用看不到任何租户数据；
 *   <li>会话级 {@code SET} 会跨借用残留——这正是必须用 {@code SET LOCAL} 的原因，用例把泄漏固定下来，
 *       避免以后有人把它改成 {@code SET} 还以为等价；
 *   <li>并发借用不同连接时，两个租户各看各的，互不可见。
 * </ol>
 *
 * <p>未提供 {@code -Dplatform.db.url} 时整类跳过：普通 {@code mvn verify} 不依赖数据库。
 * 真实执行由 {@code scripts\db-verify.ps1} 和 CI 的 {@code db} 作业带上参数触发。
 */
class ConnectionPoolTenantContextTest {

    private static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String SLUG_A = "tenant-a";
    private static final String SLUG_B = "tenant-b";

    private static String url;
    private static String user;
    private static String password;
    private static HikariDataSource pool;

    @BeforeAll
    static void openPool() throws Exception {
        url = System.getProperty("platform.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未提供 -Dplatform.db.url，跳过真实连接池用例");
        user = System.getProperty("platform.db.user", "platform_app");
        password = System.getProperty("platform.db.password", "");

        // 池里只放一条连接：任何两次借用都必然落在同一个物理连接上，泄漏无处可藏
        pool = new HikariDataSource(config(1, "tenant-context-single"));

        try (Connection c = pool.getConnection()) {
            Assumptions.assumeTrue(countVisible(c, TENANT_A) > 0,
                    "目标库缺少种子租户，先执行 tests\\db\\01-seed-bulk.sql");
        }
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void transactionScopedContextDoesNotLeakAfterCommit() throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            exec(c, "SET LOCAL app.tenant_id = '" + TENANT_A + "'");
            assertEquals(1, queryInt(c, "SELECT count(*) FROM tenancy_tenant"),
                    "事务内应当只看到本租户");
            c.commit();

            c.setAutoCommit(true);
            assertEquals(0, queryInt(c, "SELECT count(*) FROM tenancy_tenant"),
                    "提交后同一条连接不得残留租户上下文");
            assertNull(queryText(c, "SELECT nullif(current_setting('app.tenant_id', true), '')"),
                    "SET LOCAL 提交后变量应回到空值");
        }
    }

    @Test
    void sessionScopedContextLeaksToNextCheckout() throws SQLException {
        try (Connection c = pool.getConnection()) {
            exec(c, "SET app.tenant_id = '" + TENANT_A + "'");
        }
        try (Connection c = pool.getConnection()) {
            // 这条断言描述的是危险行为本身：SET 不随连接归还复位。
            // 业务代码若用 SET 而不是 SET LOCAL，下一个请求会直接读到别人的数据。
            assertEquals(1, queryInt(c, "SELECT count(*) FROM tenancy_tenant"),
                    "会话级 SET 会泄漏到下一次借用，说明只能用 SET LOCAL");
            exec(c, "RESET app.tenant_id");
            assertEquals(0, queryInt(c, "SELECT count(*) FROM tenancy_tenant"),
                    "RESET 之后上下文才被清掉");
        }
    }

    @Test
    void concurrentCheckoutsStayIsolated() throws Exception {
        try (HikariDataSource two = new HikariDataSource(config(2, "tenant-context-pair"))) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            List<Future<String>> futures = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            try {
                for (int i = 0; i < 40; i++) {
                    String tenantId = i % 2 == 0 ? TENANT_A : TENANT_B;
                    String expectedSlug = i % 2 == 0 ? SLUG_A : SLUG_B;
                    futures.add(workers.submit(() -> visibleSlug(two, tenantId, expectedSlug)));
                    expected.add(expectedSlug);
                }
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        assertEquals(expected.get(i), futures.get(i).get(30, TimeUnit.SECONDS),
                                "并发借用时租户上下文串了");
                    } catch (ExecutionException e) {
                        fail("并发借用失败：" + e.getCause(), e.getCause());
                    }
                }
            } finally {
                workers.shutdownNow();
            }
        }
    }

    private static String visibleSlug(HikariDataSource dataSource, String tenantId, String expectedSlug)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, "SET LOCAL app.tenant_id = '" + tenantId + "'");
                assertEquals(1, queryInt(c, "SELECT count(*) FROM tenancy_tenant"),
                        "连接池借出的连接应只看到 " + expectedSlug);
                return queryText(c, "SELECT slug FROM tenancy_tenant");
            } finally {
                c.rollback();
            }
        }
    }

    private static HikariConfig config(int poolSize, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName(name);
        config.setConnectionTimeout(5000);
        return config;
    }

    private static int countVisible(Connection c, String tenantId) throws SQLException {
        c.setAutoCommit(false);
        try {
            exec(c, "SET LOCAL app.tenant_id = '" + tenantId + "'");
            return queryInt(c, "SELECT count(*) FROM tenancy_tenant");
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "查询没有返回行：" + sql);
            return rs.getInt(1);
        }
    }

    private static String queryText(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "查询没有返回行：" + sql);
            return rs.getString(1);
        }
    }
}
