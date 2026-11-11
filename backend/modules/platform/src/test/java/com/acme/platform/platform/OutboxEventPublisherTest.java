package com.acme.platform.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class OutboxEventPublisherTest {

    private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    private final OutboxEventPublisher publisher =
            new OutboxEventPublisher(jdbcTemplate, new ObjectMapper(), "platform-api");

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void writesEventWithCallerTenantCorrelationAndTrace() {
        UUID tenantId = UUID.randomUUID();
        RequestContextHolder.set(new RequestContext(tenantId, "corr-smoke", traceId()));

        UUID eventId = publisher.publish("platform.smoke.ping.v1", Map.of("message", "hi"));

        assertThat(jdbcTemplate.sql).contains("INSERT INTO platform_outbox");
        assertThat(jdbcTemplate.args).hasSize(9);
        assertThat(jdbcTemplate.args[0]).isEqualTo(eventId);
        assertThat(jdbcTemplate.args[1]).isEqualTo(tenantId);
        assertThat(jdbcTemplate.args[2]).isEqualTo("platform.smoke.ping.v1");
        assertThat(jdbcTemplate.args[3]).isEqualTo(1);
        assertThat(jdbcTemplate.args[4]).isEqualTo("platform-api");
        assertThat(jdbcTemplate.args[5]).isEqualTo("corr-smoke");
        assertThat(jdbcTemplate.args[6]).isEqualTo(traceId());
        assertThat(jdbcTemplate.args[7]).isInstanceOf(OffsetDateTime.class);
        assertThat(jdbcTemplate.args[8]).isEqualTo("{\"message\":\"hi\"}");
    }

    @Test
    void derivesSchemaVersionFromEventNameSuffix() {
        RequestContextHolder.set(context());

        publisher.publish("worklog.entry.exported.v3", Map.of());

        assertThat(jdbcTemplate.args[3]).isEqualTo(3);
    }

    @Test
    void rejectsEventNameOutsideConvention() {
        RequestContextHolder.set(context());

        assertThatThrownBy(() -> publisher.publish("Platform.Smoke", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Platform.Smoke");
    }

    @Test
    void rejectsEventWithoutRequestContext() {
        assertThatThrownBy(() -> publisher.publish("platform.smoke.ping.v1", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请求上下文");
    }

    @Test
    void rejectsEventWithoutTenant() {
        RequestContextHolder.set(new RequestContext(null, "corr-1", traceId()));

        assertThatThrownBy(() -> publisher.publish("platform.smoke.ping.v1", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户");
    }

    private static RequestContext context() {
        return new RequestContext(UUID.randomUUID(), "corr-1", traceId());
    }

    private static String traceId() {
        return "0123456789abcdef0123456789abcdef";
    }

    /** 记录一次 INSERT 的替身，用来断言写入的参数而不连数据库。 */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private String sql;
        private Object[] args;

        private RecordingJdbcTemplate() {
            super(new DriverManagerDataSource());
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}
