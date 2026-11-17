package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 查询时权限过滤搜索：SQL 直接联查频道/项目成员，不依赖外部索引。
 *
 * <p>Phase 3 不引入索引服务；未来接入外部索引时，索引只作候选召回，
 * 返回前仍必须回到这里做同一套可见性判断。
 */
@Service
public class SearchService {

    private static final int MIN_QUERY = 2;
    private static final int MAX_QUERY = 80;

    private final CollaborationStore store;
    private final TenantTransactions transactions;

    public SearchService(CollaborationStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public List<CollaborationStore.SearchHit> search(CollabViewer viewer, String query, int limit) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.length() < MIN_QUERY) {
            throw ApiException.badRequest("SEARCH_QUERY_TOO_SHORT",
                    "搜索词至少 " + MIN_QUERY + " 个字符");
        }
        if (trimmed.length() > MAX_QUERY) {
            throw ApiException.badRequest("SEARCH_QUERY_TOO_LONG",
                    "搜索词不能超过 " + MAX_QUERY + " 个字符");
        }
        return transactions.inTenant(context -> store.search(viewer, trimmed, limit));
    }
}
