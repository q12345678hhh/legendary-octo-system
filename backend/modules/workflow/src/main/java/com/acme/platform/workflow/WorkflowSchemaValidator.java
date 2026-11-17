package com.acme.platform.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 工作流定义校验：纯函数，事务外即可单测。
 *
 * <p>只接受固定节点枚举与结构化条件，不解释脚本或任意表达式；
 * 校验失败一次性返回全部原因，便于定义管理页面逐条展示。
 */
@Component
public class WorkflowSchemaValidator {

    public static final Set<String> NODE_TYPES = Set.of(
            "start", "approval", "exclusive", "parallel_split", "parallel_join",
            "timer", "service", "end");
    public static final Set<String> ASSIGNEE_KINDS = Set.of(
            "person", "position", "unit_head", "reporting_chain", "role", "business_owner");
    public static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "in", "contains");
    public static final int MAX_NODES = 64;
    public static final int MAX_EDGES = 256;

    public List<String> validate(JsonNode schema) {
        List<String> errors = new ArrayList<>();
        if (schema == null || !schema.isObject()) {
            return List.of("schema 必须是 JSON 对象");
        }
        JsonNode nodes = schema.path("nodes");
        JsonNode edges = schema.path("edges");
        if (!nodes.isArray() || nodes.isEmpty()) {
            errors.add("nodes 必须是非空数组");
        }
        if (!edges.isArray()) {
            errors.add("edges 必须是数组");
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        if (nodes.size() > MAX_NODES) {
            errors.add("节点数不能超过 " + MAX_NODES);
        }
        if (edges.size() > MAX_EDGES) {
            errors.add("边数不能超过 " + MAX_EDGES);
        }

        Set<String> keys = new LinkedHashSet<>();
        int startCount = 0;
        String startKey = null;
        for (JsonNode node : nodes) {
            String key = text(node, "key");
            String type = text(node, "type");
            if (key.isBlank()) {
                errors.add("节点缺少 key");
                continue;
            }
            if (!keys.add(key)) {
                errors.add("节点 key 重复：" + key);
            }
            if (!NODE_TYPES.contains(type)) {
                errors.add("未知节点类型：" + key + " -> " + type);
                continue;
            }
            switch (type) {
                case "start" -> {
                    startCount++;
                    startKey = key;
                }
                case "approval" -> validateApproval(key, node, errors);
                case "exclusive" -> {
                    String fallback = text(node, "default");
                    if (!fallback.isBlank() && !containsEdgeKey(edges, fallback)) {
                        errors.add("exclusive 默认边不存在：" + key + " -> " + fallback);
                    }
                }
                case "timer" -> {
                    if (!node.path("delayMinutes").isNumber()
                            || node.path("delayMinutes").asInt() <= 0) {
                        errors.add("timer 节点缺少正数 delayMinutes：" + key);
                    }
                }
                case "service" -> {
                    if (text(node, "handler").isBlank()) {
                        errors.add("service 节点缺少 handler：" + key);
                    }
                    if (node.has("maxAttempts") && (!node.path("maxAttempts").isNumber()
                            || node.path("maxAttempts").asInt() <= 0)) {
                        errors.add("service 节点 maxAttempts 必须为正数：" + key);
                    }
                }
                default -> {
                    // end / parallel_split / parallel_join 无额外必填项
                }
            }
        }
        if (startCount != 1) {
            errors.add("必须恰好有一个 start 节点，实际 " + startCount);
        }

        Set<String> edgeKeys = new HashSet<>();
        for (JsonNode edge : edges) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (!keys.contains(from)) {
                errors.add("边引用了不存在的起点：" + from);
            }
            if (!keys.contains(to)) {
                errors.add("边引用了不存在的终点：" + to);
            }
            boolean hasCondition = edge.has("condition") && !edge.path("condition").isNull();
            boolean hasWhen = !text(edge, "when").isBlank();
            if (hasCondition && hasWhen) {
                errors.add("边不能同时声明 condition 与 when：" + from + " -> " + to);
            }
            if (hasWhen) {
                String when = text(edge, "when");
                if (!"approved".equals(when) && !"returned".equals(when)) {
                    errors.add("when 只能是 approved 或 returned：" + from + " -> " + to);
                }
            }
            if (hasCondition) {
                validateCondition(edge.path("condition"), "边 " + from + " -> " + to, errors);
            }
            String edgeKey = text(edge, "key");
            if (!edgeKey.isBlank() && !edgeKeys.add(edgeKey)) {
                errors.add("边 key 重复：" + edgeKey);
            }
        }

        if (errors.isEmpty()) {
            errors.addAll(unreachable(keys, edges, startKey));
        }
        return errors;
    }

    public void validateOrThrow(JsonNode schema) {
        List<String> errors = validate(schema);
        if (!errors.isEmpty()) {
            throw com.acme.platform.platform.ApiException.badRequest(
                    "WORKFLOW_SCHEMA_INVALID", String.join("；", errors));
        }
    }

    private void validateApproval(String key, JsonNode node, List<String> errors) {
        JsonNode assignee = node.path("assignee");
        if (!assignee.isObject()) {
            errors.add("approval 节点缺少 assignee：" + key);
            return;
        }
        String kind = text(assignee, "kind");
        if (!ASSIGNEE_KINDS.contains(kind)) {
            errors.add("approval 节点 assignee.kind 非法：" + key + " -> " + kind);
            return;
        }
        switch (kind) {
            case "person" -> {
                if (text(assignee, "personId").isBlank()) {
                    errors.add("person 审批人缺少 personId：" + key);
                }
            }
            case "position" -> {
                if (text(assignee, "positionId").isBlank()) {
                    errors.add("position 审批人缺少 positionId：" + key);
                }
            }
            case "role" -> {
                if (text(assignee, "roleCode").isBlank()) {
                    errors.add("role 审批人缺少 roleCode：" + key);
                }
            }
            case "reporting_chain" -> {
                if (!assignee.path("depth").isNumber() || assignee.path("depth").asInt() < 1) {
                    errors.add("reporting_chain 审批人 depth 必须为正数：" + key);
                }
            }
            default -> {
                // unit_head / business_owner 无需额外字段
            }
        }
        String mode = text(node, "approvalMode");
        if (!mode.isBlank() && !"any".equals(mode) && !"all".equals(mode)) {
            errors.add("approvalMode 只能是 any 或 all：" + key);
        }
        if (node.has("slaMinutes") && (!node.path("slaMinutes").isNumber()
                || node.path("slaMinutes").asInt() <= 0)) {
            errors.add("slaMinutes 必须为正数：" + key);
        }
    }

    private void validateCondition(JsonNode condition, String where, List<String> errors) {
        if (!condition.isObject()) {
            errors.add(where + " 的条件必须是对象");
            return;
        }
        if (condition.has("all") || condition.has("any")) {
            String field = condition.has("all") ? "all" : "any";
            JsonNode items = condition.path(field);
            if (!items.isArray() || items.isEmpty()) {
                errors.add(where + " 的 " + field + " 必须是非空数组");
                return;
            }
            for (JsonNode item : items) {
                validateCondition(item, where, errors);
            }
            return;
        }
        if (condition.has("not")) {
            validateCondition(condition.path("not"), where, errors);
            return;
        }
        String var = text(condition, "var");
        String op = text(condition, "op");
        if (var.isBlank()) {
            errors.add(where + " 的条件缺少 var");
        }
        if (!OPERATORS.contains(op)) {
            errors.add(where + " 的条件 op 非法：" + op);
        }
        if (!condition.has("value")) {
            errors.add(where + " 的条件缺少 value");
        }
    }

    private static boolean containsEdgeKey(JsonNode edges, String key) {
        for (JsonNode edge : edges) {
            if (key.equals(text(edge, "key"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> unreachable(Set<String> keys, JsonNode edges, String start) {
        if (start == null) {
            return List.of("找不到 start 节点");
        }
        Set<String> reachable = new HashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (JsonNode edge : edges) {
                if (current.equals(text(edge, "from"))) {
                    String to = text(edge, "to");
                    if (keys.contains(to) && reachable.add(to)) {
                        queue.add(to);
                    }
                }
            }
        }
        List<String> errors = new ArrayList<>();
        for (String key : keys) {
            if (!reachable.contains(key)) {
                errors.add("节点不可达：" + key);
            }
        }
        return errors;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }
}
