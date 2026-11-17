package com.acme.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 条件求值：固定操作符 + 缺失/类型不匹配安全返回 false。 */
class WorkflowConditionEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowConditionEvaluator evaluator = new WorkflowConditionEvaluator();
    private final Map<String, Object> variables = Map.of(
            "amount", 5000,
            "department", "sales",
            "tags", List.of("urgent", "finance"));

    private boolean matches(String condition) {
        JsonNode node = mapper.readTree(condition);
        return evaluator.matches(node, variables);
    }

    @Test
    void supportsAllAnyNot() {
        assertThat(matches("""
                {"all": [
                  {"var": "amount", "op": "gte", "value": 1000},
                  {"var": "department", "op": "eq", "value": "sales"}
                ]}
                """)).isTrue();
        assertThat(matches("""
                {"any": [
                  {"var": "amount", "op": "lt", "value": 1},
                  {"var": "department", "op": "eq", "value": "sales"}
                ]}
                """)).isTrue();
        assertThat(matches("""
                {"not": {"var": "department", "op": "eq", "value": "sales"}}
                """)).isFalse();
    }

    @Test
    void supportsNumericAndStringOperators() {
        assertThat(matches("{\"var\": \"amount\", \"op\": \"gt\", \"value\": 4999}")).isTrue();
        assertThat(matches("{\"var\": \"amount\", \"op\": \"lte\", \"value\": 4999}")).isFalse();
        assertThat(matches("{\"var\": \"department\", \"op\": \"ne\", \"value\": \"hr\"}")).isTrue();
        assertThat(matches("{\"var\": \"department\", \"op\": \"in\", \"value\": [\"hr\", \"sales\"]}"))
                .isTrue();
        assertThat(matches("{\"var\": \"tags\", \"op\": \"contains\", \"value\": \"urgent\"}"))
                .isTrue();
    }

    @Test
    void missingVariableAndTypeMismatchNeverMatch() {
        assertThat(matches("{\"var\": \"missing\", \"op\": \"eq\", \"value\": 1}")).isFalse();
        assertThat(matches("{\"var\": \"department\", \"op\": \"gt\", \"value\": 1}")).isFalse();
        assertThat(matches("{\"var\": \"amount\", \"op\": \"contains\", \"value\": 1}")).isFalse();
    }

    @Test
    void nullConditionMatches() {
        assertThat(evaluator.matches(null, variables)).isTrue();
    }
}
