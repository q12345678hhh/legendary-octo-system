package com.acme.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 定义校验必须是纯函数：所有拒绝规则都能在事务外单测。 */
class WorkflowSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowSchemaValidator validator = new WorkflowSchemaValidator();

    private JsonNode json(String value) {
        return mapper.readTree(value);
    }

    @Test
    void acceptsMinimalApprovalFlow() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [
                    {"key": "start", "type": "start"},
                    {"key": "approve", "type": "approval",
                     "assignee": {"kind": "unit_head"},
                     "approvalMode": "any", "slaMinutes": 60},
                    {"key": "end", "type": "end"}
                  ],
                  "edges": [
                    {"from": "start", "to": "approve"},
                    {"from": "approve", "to": "end", "when": "approved"}
                  ]
                }
                """));
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsDuplicateAndUnknownNode() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [
                    {"key": "start", "type": "start"},
                    {"key": "start", "type": "bogus"}
                  ],
                  "edges": []
                }
                """));
        assertThat(String.join("；", errors)).contains("节点 key 重复", "未知节点类型");
    }

    @Test
    void rejectsDanglingEdgeAndMissingStart() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [{"key": "a", "type": "end"}],
                  "edges": [{"from": "a", "to": "missing"}]
                }
                """));
        assertThat(String.join("；", errors)).contains("必须恰好有一个 start", "不存在的终点");
    }

    @Test
    void rejectsUnreachableNode() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [
                    {"key": "start", "type": "start"},
                    {"key": "end", "type": "end"},
                    {"key": "island", "type": "end"}
                  ],
                  "edges": [{"from": "start", "to": "end"}]
                }
                """));
        assertThat(String.join("；", errors)).contains("节点不可达：island");
    }

    @Test
    void rejectsApprovalWithoutAssignee() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [
                    {"key": "start", "type": "start"},
                    {"key": "approve", "type": "approval"},
                    {"key": "end", "type": "end"}
                  ],
                  "edges": [
                    {"from": "start", "to": "approve"},
                    {"from": "approve", "to": "end"}
                  ]
                }
                """));
        assertThat(String.join("；", errors)).contains("approval 节点缺少 assignee");
    }

    @Test
    void rejectsTimerWithoutDelayAndServiceWithoutHandler() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [
                    {"key": "start", "type": "start"},
                    {"key": "wait", "type": "timer"},
                    {"key": "call", "type": "service"},
                    {"key": "end", "type": "end"}
                  ],
                  "edges": [
                    {"from": "start", "to": "wait"},
                    {"from": "wait", "to": "call"},
                    {"from": "call", "to": "end"}
                  ]
                }
                """));
        assertThat(String.join("；", errors))
                .contains("timer 节点缺少正数 delayMinutes", "service 节点缺少 handler");
    }

    @Test
    void rejectsConditionAndWhenTogetherAndBadOperator() {
        List<String> errors = validator.validate(json("""
                {
                  "nodes": [
                    {"key": "start", "type": "start"},
                    {"key": "gate", "type": "exclusive"},
                    {"key": "end", "type": "end"}
                  ],
                  "edges": [
                    {"from": "start", "to": "gate"},
                    {"from": "gate", "to": "end", "when": "approved",
                     "condition": {"var": "amount", "op": "between", "value": 1}}
                  ]
                }
                """));
        assertThat(String.join("；", errors))
                .contains("不能同时声明 condition 与 when", "op 非法");
    }

    @Test
    void rejectsTooManyNodes() {
        StringBuilder nodes = new StringBuilder();
        for (int index = 0; index < 65; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{\"key\":\"n").append(index).append('"')
                    .append(index == 0 ? ",\"type\":\"start\"" : ",\"type\":\"end\"")
                    .append('}');
        }
        List<String> errors = validator.validate(json(
                "{\"nodes\":[" + nodes + "],\"edges\":[]}"));
        assertThat(String.join("；", errors)).contains("节点数不能超过 64");
    }
}
