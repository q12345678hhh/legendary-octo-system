package com.acme.platform.workflow;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 结构化条件求值：只支持 all / any / not 与固定比较符，不解释脚本。
 *
 * <p>缺失变量、类型不匹配一律视为「不命中」，由调用方决定是否走默认边或写异常。
 */
@Component
public class WorkflowConditionEvaluator {

    public boolean matches(JsonNode condition, Map<String, Object> variables) {
        if (condition == null || condition.isNull() || condition.isMissingNode()) {
            return true;
        }
        if (!condition.isObject()) {
            return false;
        }
        if (condition.has("all")) {
            for (JsonNode item : condition.path("all")) {
                if (!matches(item, variables)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.has("any")) {
            for (JsonNode item : condition.path("any")) {
                if (matches(item, variables)) {
                    return true;
                }
            }
            return false;
        }
        if (condition.has("not")) {
            return !matches(condition.path("not"), variables);
        }
        return compare(condition, variables);
    }

    private boolean compare(JsonNode condition, Map<String, Object> variables) {
        String var = WorkflowSchemaValidator.text(condition, "var");
        String op = WorkflowSchemaValidator.text(condition, "op");
        if (var.isBlank()) {
            return false;
        }
        Object actual = lookup(variables, var);
        if (actual == null) {
            return false;
        }
        JsonNode expected = condition.path("value");
        Integer comparison = compareNumbers(actual, expected);
        return switch (op) {
            case "eq" -> equal(actual, expected);
            case "ne" -> !equal(actual, expected);
            case "gt" -> comparison != null && comparison > 0;
            case "gte" -> comparison != null && comparison >= 0;
            case "lt" -> comparison != null && comparison < 0;
            case "lte" -> comparison != null && comparison <= 0;
            case "in" -> in(actual, expected);
            case "contains" -> contains(actual, expected);
            default -> false;
        };
    }

    private static Object lookup(Map<String, Object> variables, String path) {
        if (variables == null) {
            return null;
        }
        if (variables.containsKey(path)) {
            return variables.get(path);
        }
        Object current = variables;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private static boolean equal(Object actual, JsonNode expected) {
        if (expected.isNumber() && actual instanceof Number number) {
            return number(actual).compareTo(number(expected)) == 0;
        }
        if (expected.isBoolean() && actual instanceof Boolean bool) {
            return bool == expected.asBoolean();
        }
        return String.valueOf(actual).equals(expected.isTextual()
                ? expected.asText() : expected.toString());
    }

    private static Integer compareNumbers(Object actual, JsonNode expected) {
        if (!expected.isNumber() || !(actual instanceof Number)) {
            return null;
        }
        return number(actual).compareTo(number(expected));
    }

    private static boolean in(Object actual, JsonNode expected) {
        if (!expected.isArray()) {
            return false;
        }
        for (JsonNode item : expected) {
            if (equal(actual, item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Object actual, JsonNode expected) {
        if (actual instanceof String text) {
            return expected.isTextual() && text.contains(expected.asText());
        }
        if (actual instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (equal(item, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal number(JsonNode value) {
        return value.decimalValue();
    }
}
