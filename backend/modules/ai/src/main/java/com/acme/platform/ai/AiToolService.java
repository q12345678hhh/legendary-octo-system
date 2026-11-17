package com.acme.platform.ai;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * AI 工具目录：登记、启停与评分。
 *
 * <p>首期只做租户级元数据，不执行工具调用；调用凭据由后续凭证模块托管。
 */
@Service
public class AiToolService {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9._-]{2,63}$");

    private final AiToolStore store;
    private final TenantTransactions transactions;

    public AiToolService(AiToolStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public record ToolView(UUID id, String code, String name, String description,
                           String endpointUrl, String inputSchema, String status, Integer rating,
                           String createdBy, String ratedBy, long version,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record RegisterInput(String code, String name, String description, String endpointUrl,
                                String inputSchema) {
    }

    public List<ToolView> list() {
        return transactions.inTenant(context -> store.list().stream().map(this::view).toList());
    }

    /** 供接口在授权判定前取登记人/评分人属性，避免把职责分离属性写死在控制器里。 */
    public ToolView get(UUID id) {
        return transactions.inTenant(context -> view(require(id)));
    }

    public ToolView register(RegisterInput input, String actor) {
        String code = text(input.code());
        if (!CODE.matcher(code).matches()) {
            throw ApiException.badRequest("AI_TOOL_CODE_INVALID",
                    "工具编码需以小写字母开头，3-64 位，可含 . _ -");
        }
        String name = text(input.name());
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest("AI_TOOL_NAME_INVALID", "工具名称必填且不超过 100 字");
        }
        String endpoint = text(input.endpointUrl());
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            throw ApiException.badRequest("AI_TOOL_ENDPOINT_INVALID",
                    "工具地址需以 http:// 或 https:// 开头");
        }
        String schemaValue = text(input.inputSchema());
        if (schemaValue.isEmpty()) {
            schemaValue = "{}";
        }
        final String schema = schemaValue;
        try {
            return transactions.inTenant(context -> {
                store.insert(code, name, text(input.description()), endpoint, schema, actor);
                return store.findByCode(code)
                        .map(this::view)
                        .orElseThrow(() -> new IllegalStateException("工具写入后不可见"));
            });
        } catch (DataAccessException error) {
            String message = error.getMessage() == null ? "" : error.getMessage();
            if (message.contains("ai_tool_tenant_code_unique")) {
                throw ApiException.conflict("AI_TOOL_CODE_EXISTS", "该工具编码已存在");
            }
            if (message.contains("invalid input syntax for type json")
                    || message.contains("ai_tool_schema_check")) {
                throw ApiException.badRequest("AI_TOOL_SCHEMA_INVALID",
                        "inputSchema 必须是合法的 JSON 对象");
            }
            throw error;
        }
    }

    public ToolView setStatus(UUID id, String status, Long expectedVersion) {
        String target = text(status);
        if (!"enabled".equals(target) && !"disabled".equals(target)) {
            throw ApiException.badRequest("AI_TOOL_STATUS_INVALID",
                    "工具状态只能是 enabled 或 disabled");
        }
        return transactions.inTenant(context -> {
            AiToolStore.ToolRow row = require(id);
            if ("enabled".equals(target) && row.rating() == null) {
                // 启用前必须先由登记人以外的人评分，授权判定里的 ratedBy 属性才有值。
                throw ApiException.unprocessable("AI_TOOL_RATING_REQUIRED",
                        "启用前请先由他人完成评分");
            }
            if (target.equals(row.status())) {
                return view(row);
            }
            if (expectedVersion != null && expectedVersion != row.version()) {
                throw versionConflict();
            }
            if (!store.updateStatus(id, target, row.version())) {
                throw versionConflict();
            }
            return view(require(id));
        });
    }

    public ToolView rate(UUID id, Integer rating, Long expectedVersion, String actor) {
        if (rating == null || rating < 1 || rating > 5) {
            throw ApiException.badRequest("AI_TOOL_RATING_INVALID", "评分只能是 1-5");
        }
        return transactions.inTenant(context -> {
            AiToolStore.ToolRow row = require(id);
            if (expectedVersion != null && expectedVersion != row.version()) {
                throw versionConflict();
            }
            if (!store.updateRating(id, rating, actor, row.version())) {
                throw versionConflict();
            }
            return view(require(id));
        });
    }

    private AiToolStore.ToolRow require(UUID id) {
        return store.find(id).orElseThrow(() ->
                ApiException.notFound("AI_TOOL_NOT_FOUND", "工具不存在"));
    }

    private ToolView view(AiToolStore.ToolRow row) {
        return new ToolView(row.id(), row.code(), row.name(), row.description(),
                row.endpointUrl(), row.inputSchema(), row.status(), row.rating(),
                row.createdBy(), row.ratedBy(), row.version(),
                row.createdAt(), row.updatedAt());
    }

    private static ApiException versionConflict() {
        return ApiException.conflict("AI_TOOL_VERSION_CONFLICT", "工具已被其他人修改，请刷新后重试");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
