package com.acme.platform.workflow;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 定义版本化管理：草稿、修改草稿、发布、退役。
 *
 * <p>发布时校验 schema 并把同 key 的旧 published 版本置 retired；发布后不可再改。
 */
@Service
public class WorkflowDefinitionService {

    private final WorkflowStore store;
    private final TenantTransactions transactions;
    private final WorkflowSchemaValidator validator;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(WorkflowStore store, TenantTransactions transactions,
                                     WorkflowSchemaValidator validator,
                                     ObjectMapper objectMapper) {
        this.store = store;
        this.transactions = transactions;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public record DefinitionView(UUID id, String key, int version, String name, String status,
                                 String schema, String schemaHash, String createdBy,
                                 OffsetDateTime publishedAt) {
    }

    public List<DefinitionView> list() {
        return transactions.inTenant(context -> store.definitions().stream()
                .map(WorkflowDefinitionService::toView).toList());
    }

    public DefinitionView createDraft(String key, String name, String schemaJson, String createdBy) {
        if (key == null || !key.matches("^[a-z][a-z0-9._-]{2,63}$")) {
            throw ApiException.badRequest("WORKFLOW_KEY_INVALID",
                    "定义 key 必须以小写字母开头，只含小写字母、数字、点、下划线与连字符");
        }
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("WORKFLOW_NAME_REQUIRED", "定义名称不能为空");
        }
        JsonNode schema = parse(schemaJson);
        List<String> errors = validator.validate(schema);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("WORKFLOW_SCHEMA_INVALID", String.join("；", errors));
        }
        return transactions.inTenant(context -> {
            int version = store.maxVersion(key) + 1;
            UUID id = store.insertDefinition(key, version, name.trim(), schemaJson,
                    hash(schemaJson), createdBy == null || createdBy.isBlank()
                            ? context.subjectId() : createdBy);
            return store.definition(id).map(WorkflowDefinitionService::toView).orElseThrow();
        });
    }

    public DefinitionView updateDraft(UUID id, String name, String schemaJson) {
        JsonNode schema = parse(schemaJson);
        List<String> errors = validator.validate(schema);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("WORKFLOW_SCHEMA_INVALID", String.join("；", errors));
        }
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("WORKFLOW_NAME_REQUIRED", "定义名称不能为空");
        }
        return transactions.inTenant(context -> {
            WorkflowStore.DefinitionRow row = store.definition(id)
                    .orElseThrow(() -> ApiException.notFound("WORKFLOW_DEFINITION_NOT_FOUND",
                            "工作流定义不存在：" + id));
            if (!"draft".equals(row.status())) {
                throw ApiException.conflict("WORKFLOW_DEFINITION_NOT_DRAFT",
                        "只有草稿定义可以修改，当前状态：" + row.status());
            }
            store.updateDefinition(id, name.trim(), schemaJson, hash(schemaJson));
            return store.definition(id).map(WorkflowDefinitionService::toView).orElseThrow();
        });
    }

    public DefinitionView publish(UUID id) {
        return transactions.inTenant(context -> {
            WorkflowStore.DefinitionRow row = store.definition(id)
                    .orElseThrow(() -> ApiException.notFound("WORKFLOW_DEFINITION_NOT_FOUND",
                            "工作流定义不存在：" + id));
            if (!"draft".equals(row.status())) {
                throw ApiException.conflict("WORKFLOW_DEFINITION_NOT_DRAFT",
                        "只有草稿定义可以发布，当前状态：" + row.status());
            }
            List<String> errors = validator.validate(parse(row.schema()));
            if (!errors.isEmpty()) {
                throw ApiException.badRequest("WORKFLOW_SCHEMA_INVALID",
                        String.join("；", errors));
            }
            store.retireOtherPublished(row.key(), row.id());
            store.markPublished(row.id());
            return store.definition(id).map(WorkflowDefinitionService::toView).orElseThrow();
        });
    }

    public DefinitionView retire(UUID id) {
        return transactions.inTenant(context -> {
            WorkflowStore.DefinitionRow row = store.definition(id)
                    .orElseThrow(() -> ApiException.notFound("WORKFLOW_DEFINITION_NOT_FOUND",
                            "工作流定义不存在：" + id));
            if (!"published".equals(row.status())) {
                throw ApiException.conflict("WORKFLOW_DEFINITION_NOT_PUBLISHED",
                        "只有已发布定义可以退役，当前状态：" + row.status());
            }
            store.markRetired(id);
            return store.definition(id).map(WorkflowDefinitionService::toView).orElseThrow();
        });
    }

    public boolean hasPublished(String key) {
        return transactions.inTenant(context -> store.publishedDefinition(key).isPresent());
    }

    private JsonNode parse(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw ApiException.badRequest("WORKFLOW_SCHEMA_REQUIRED", "缺少工作流 schema");
        }
        try {
            return objectMapper.readTree(schemaJson);
        } catch (RuntimeException error) {
            throw ApiException.badRequest("WORKFLOW_SCHEMA_INVALID", "schema 不是合法 JSON");
        }
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static DefinitionView toView(WorkflowStore.DefinitionRow row) {
        return new DefinitionView(row.id(), row.key(), row.version(), row.name(), row.status(),
                row.schema(), row.schemaHash(), row.createdBy(), row.publishedAt());
    }
}
