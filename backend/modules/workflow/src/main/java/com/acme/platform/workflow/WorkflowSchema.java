package com.acme.platform.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作流定义的只读运行期视图：节点按 key 索引，边按起点索引。
 *
 * <p>只在引擎内部使用；构建前必须已通过 {@link WorkflowSchemaValidator}。
 */
public record WorkflowSchema(Map<String, Node> nodes,
                             List<Edge> edges,
                             Map<String, List<Edge>> outgoing) {

    public record Node(String key,
                       String type,
                       String defaultEdge,
                       String assigneeKind,
                       UUID assigneePersonId,
                       UUID positionId,
                       String roleCode,
                       int chainDepth,
                       String approvalMode,
                       int slaMinutes,
                       String onBreach,
                       boolean allowTransfer,
                       boolean allowReturn,
                       int delayMinutes,
                       String handler,
                       int maxAttempts,
                       String compensation) {
    }

    public record Edge(String from, String to, JsonNode condition, String when, String key) {
    }

    public static WorkflowSchema parse(ObjectMapper mapper, String json) {
        JsonNode root = mapper.readTree(json);
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();

        for (JsonNode node : root.path("nodes")) {
            JsonNode assignee = node.path("assignee");
            String key = WorkflowSchemaValidator.text(node, "key");
            nodes.put(key, new Node(
                    key,
                    WorkflowSchemaValidator.text(node, "type"),
                    WorkflowSchemaValidator.text(node, "default"),
                    WorkflowSchemaValidator.text(assignee, "kind"),
                    uuid(WorkflowSchemaValidator.text(assignee, "personId")),
                    uuid(WorkflowSchemaValidator.text(assignee, "positionId")),
                    WorkflowSchemaValidator.text(assignee, "roleCode"),
                    assignee.path("depth").isNumber() ? assignee.path("depth").asInt() : 0,
                    defaultText(WorkflowSchemaValidator.text(node, "approvalMode"), "any"),
                    node.path("slaMinutes").isNumber() ? node.path("slaMinutes").asInt() : 0,
                    defaultText(WorkflowSchemaValidator.text(node, "onBreach"), "escalate"),
                    !node.has("allowTransfer") || node.path("allowTransfer").asBoolean(true),
                    !node.has("allowReturn") || node.path("allowReturn").asBoolean(true),
                    node.path("delayMinutes").isNumber() ? node.path("delayMinutes").asInt() : 0,
                    WorkflowSchemaValidator.text(node, "handler"),
                    node.path("maxAttempts").isNumber() ? node.path("maxAttempts").asInt() : 3,
                    WorkflowSchemaValidator.text(node, "compensation")));
        }

        for (JsonNode edge : root.path("edges")) {
            Edge parsed = new Edge(
                    WorkflowSchemaValidator.text(edge, "from"),
                    WorkflowSchemaValidator.text(edge, "to"),
                    edge.has("condition") && !edge.path("condition").isNull()
                            ? edge.path("condition") : null,
                    WorkflowSchemaValidator.text(edge, "when"),
                    WorkflowSchemaValidator.text(edge, "key"));
            edges.add(parsed);
            outgoing.computeIfAbsent(parsed.from(), ignored -> new ArrayList<>()).add(parsed);
        }
        return new WorkflowSchema(Map.copyOf(nodes), List.copyOf(edges), Map.copyOf(outgoing));
    }

    public Node node(String key) {
        return nodes.get(key);
    }

    public List<Edge> outgoing(String key) {
        return outgoing.getOrDefault(key, List.of());
    }

    public int incomingCount(String key) {
        int count = 0;
        for (Edge edge : edges) {
            if (edge.to().equals(key)) {
                count++;
            }
        }
        return count;
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
