package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 知识页：部门范围内的树形页面。 */
@Service
public class PageService {

    private final CollaborationStore store;
    private final TenantTransactions transactions;

    public PageService(CollaborationStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public List<CollaborationStore.Page> list(CollabViewer viewer, UUID orgUnitId, int limit) {
        if (orgUnitId != null && !viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("PAGE_UNIT_FORBIDDEN", "无权访问该部门知识页");
        }
        return transactions.inTenant(context -> store.pages(viewer, orgUnitId, limit));
    }

    public CollaborationStore.Page requireReadable(CollabViewer viewer, UUID pageId) {
        return transactions.inTenant(context -> {
            CollaborationStore.Page page = store.page(pageId).orElseThrow(() ->
                    ApiException.notFound("PAGE_NOT_FOUND", "知识页不存在或已归档"));
            if (!viewer.seesUnit(page.orgUnitId())) {
                throw ApiException.forbidden("PAGE_FORBIDDEN", "无权访问该知识页");
            }
            return page;
        });
    }

    public CollaborationStore.Page create(CollabViewer viewer, UUID orgUnitId, UUID parentId,
                                          String title, String body) {
        if (!viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("PAGE_UNIT_FORBIDDEN", "只能在本部门创建知识页");
        }
        String safeTitle = CollabValidation.requireText(title, 200, "PAGE_TITLE", "知识页标题");
        String safeBody = CollabValidation.optionalText(body, 50000, "PAGE_BODY", "知识页正文");
        return transactions.inTenant(context -> {
            if (parentId != null) {
                CollaborationStore.Page parent = store.page(parentId).orElseThrow(() ->
                        ApiException.notFound("PAGE_PARENT_NOT_FOUND", "父知识页不存在"));
                if (!parent.orgUnitId().equals(orgUnitId)) {
                    throw ApiException.badRequest("PAGE_PARENT_UNIT_MISMATCH",
                            "父知识页必须属于同一部门");
                }
            }
            UUID id = store.insertPage(orgUnitId, parentId, safeTitle, safeBody,
                    viewer.personId());
            return store.page(id).orElseThrow();
        });
    }

    public CollaborationStore.Page update(CollabViewer viewer, UUID pageId, String title,
                                          String body) {
        requireReadable(viewer, pageId);
        String safeTitle = CollabValidation.requireText(title, 200, "PAGE_TITLE", "知识页标题");
        String safeBody = CollabValidation.optionalText(body, 50000, "PAGE_BODY", "知识页正文");
        return transactions.inTenant(context -> {
            store.updatePage(pageId, safeTitle, safeBody);
            return store.page(pageId).orElseThrow();
        });
    }
}
