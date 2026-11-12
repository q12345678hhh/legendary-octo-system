package com.acme.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 条件白名单：缺属性、格式错误与 deny 条件都不能放行。 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator(new ObjectMapper());

    @Test
    void evaluatesWhitelistedOperatorsFailClosed() {
        assertThat(evaluator.passes(
                "{\"attribute\":\"employment_status\",\"operator\":\"equals\",\"value\":\"active\"}",
                Map.of("employment_status", "active"))).isTrue();
        assertThat(evaluator.passes(
                "{\"attribute\":\"employment_status\",\"operator\":\"equals\",\"value\":\"active\"}",
                Map.of())).isFalse();
        assertThat(evaluator.passes(
                "{\"attribute\":\"amount\",\"operator\":\"gte\",\"value\":100}",
                Map.of("amount", 150))).isTrue();
        assertThat(evaluator.passes(
                "{\"attribute\":\"amount\",\"operator\":\"lte\",\"value\":100}",
                Map.of("amount", 150))).isFalse();
        assertThat(evaluator.passes(
                "{\"all\":[{\"attribute\":\"risk_level\",\"operator\":\"not_in\",\"value\":[\"high\"]},"
                        + "{\"attribute\":\"employment_status\",\"operator\":\"equals\",\"value\":\"active\"}]}",
                Map.of("risk_level", "low", "employment_status", "active"))).isTrue();
        assertThat(evaluator.passes(
                "{\"attribute\":\"risk_level\",\"operator\":\"not_in\",\"value\":[\"high\"]}",
                Map.of())).isFalse();
        assertThat(evaluator.passes("{not-json", Map.of("anything", "value"))).isFalse();
        assertThat(evaluator.isParseable("{\"attribute\":\"x\",\"operator\":\"exists\"}")).isTrue();
        assertThat(evaluator.isParseable("[]")).isFalse();
    }
}
