package com.acme.platform.expense;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.retention.RetentionTarget;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 单据接入统一回收站。
 *
 * <p>已入账单据属于会计档案，不允许普通删除；只有未入账单据可以回收，
 * 已入账单据由数据主体删除流程改写为「只匿名化 OCR」。
 */
@Component
public class ExpenseDocumentRetentionTarget implements RetentionTarget {

    private final JdbcTemplate jdbcTemplate;
    private final ExpenseStore store;

    public ExpenseDocumentRetentionTarget(JdbcTemplate jdbcTemplate, ExpenseStore store) {
        this.jdbcTemplate = jdbcTemplate;
        this.store = store;
    }

    @Override
    public String dataClass() {
        return "expense_document";
    }

    @Override
    public Optional<Map<String, Object>> snapshot(UUID objectId) {
        return jdbcTemplate.query("""
                        SELECT doc_type, status, currency, amount, created_at
                          FROM expense_document
                         WHERE id = ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("objectRef", "expense/" + objectId);
                    snapshot.put("docType", rs.getString("doc_type"));
                    snapshot.put("status", rs.getString("status"));
                    snapshot.put("currency", rs.getString("currency"));
                    snapshot.put("amount", rs.getBigDecimal("amount"));
                    snapshot.put("createdAt", String.valueOf(rs.getObject("created_at")));
                    return snapshot;
                }, objectId)
                .stream().findFirst();
    }

    @Override
    public void markRecycled(UUID objectId) {
        String status = store.findIncludingRecycled(objectId)
                .map(doc -> doc.status())
                .orElseThrow(() -> ApiException.notFound("RETENTION_OBJECT_NOT_FOUND",
                        "单据不存在或不属于当前租户"));
        if ("posted".equals(status)) {
            throw ApiException.conflict("EXPENSE_POSTED_PROTECTED",
                    "已入账单据不可删除，只能匿名化 OCR 文本");
        }
        store.markRecycled(objectId);
    }

    @Override
    public void restore(UUID objectId) {
        store.restore(objectId);
    }

    @Override
    public void purge(UUID objectId) {
        store.purgeDocument(objectId);
    }
}
