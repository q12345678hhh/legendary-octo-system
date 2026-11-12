package com.acme.platform.authorization;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 条件策略求值器。
 *
 * <p>只支持白名单条件类型（等于/不等于/属于/不属于/大于等于/小于等于/存在），
 * 缺少属性一律按不通过处理；解析失败同样不通过，绝不因格式错误放行。
 */
@Component
public class ConditionEvaluator {

    private final ObjectMapper objectMapper;

    public ConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean passes(String conditionJson, Map<String, Object> attributes) {
        if (conditionJson == null || conditionJson.isBlank()) {
            return true;
        }
        try {
            return evaluate(objectMapper.readTree(conditionJson), attributes);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isParseable(String conditionJson) {
        if (conditionJson == null || conditionJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(conditionJson);
            return node != null && node.isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean evaluate(JsonNode node, Map<String, Object> attributes) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.has("all")) {
            for (JsonNode child : node.get("all")) {
                if (!evaluate(child, attributes)) {
                    return false;
                }
            }
            return true;
        }
        if (node.has("any")) {
            for (JsonNode child : node.get("any")) {
                if (evaluate(child, attributes)) {
                    return true;
                }
            }
            return false;
        }
        if (node.has("not")) {
            return !evaluate(node.get("not"), attributes);
        }
        String attribute = node.path("attribute").asString();
        String operator = node.path("operator").asString();
        if (attribute == null || attribute.isBlank() || operator == null || operator.isBlank()) {
            return false;
        }
        Object actual = attributes.get(attribute);
        if ("exists".equals(operator)) {
            return actual != null;
        }
        if (actual == null) {
            return false;
        }
        JsonNode expected = node.get("value");
        return switch (operator) {
            case "equals" -> equalsValue(actual, expected);
            case "not_equals" -> !equalsValue(actual, expected);
            case "in" -> inValues(actual, expected);
            case "not_in" -> !inValues(actual, expected);
            case "gte" -> compare(actual, expected) >= 0;
            case "lte" -> compare(actual, expected) <= 0;
            default -> false;
        };
    }

    private boolean equalsValue(Object actual, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            return false;
        }
        return String.valueOf(actual).equals(expected.asString());
    }

    private boolean inValues(Object actual, JsonNode expected) {
        if (expected == null || !expected.isArray()) {
            return false;
        }
        String value = String.valueOf(actual);
        for (JsonNode item : expected) {
            if (value.equals(item.asString())) {
                return true;
            }
        }
        return false;
    }

    private int compare(Object actual, JsonNode expected) {
        try {
            BigDecimal left = new BigDecimal(String.valueOf(actual));
            BigDecimal right = new BigDecimal(expected.asString());
            return left.compareTo(right);
        } catch (Exception e) {
            return Integer.MIN_VALUE / 2;
        }
    }
}
