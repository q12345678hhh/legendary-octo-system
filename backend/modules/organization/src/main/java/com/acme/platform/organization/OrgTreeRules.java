package com.acme.platform.organization;

import com.acme.platform.platform.ApiException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 组织树规则：禁止环、禁止自指。
 *
 * <p>只做纯计算，父级查询由调用方以函数传入，便于单元测试覆盖深链与恶意构造的环。
 * 限制遍历步数，即使数据被人工改成环也不会把请求拖死。
 */
public final class OrgTreeRules {

    private static final int MAX_DEPTH = 1000;

    private OrgTreeRules() {
    }

    public static void ensureNoCycle(UUID unitId,
                                     UUID targetParentId,
                                     Function<UUID, Optional<UUID>> parentLookup) {
        if (targetParentId == null) {
            return;
        }
        if (targetParentId.equals(unitId)) {
            throw ApiException.unprocessable("ORG_UNIT_CYCLE", "部门不能挂到自己下面");
        }
        Set<UUID> visited = new HashSet<>();
        UUID current = targetParentId;
        int depth = 0;
        while (current != null && depth++ < MAX_DEPTH) {
            if (current.equals(unitId)) {
                throw ApiException.unprocessable("ORG_UNIT_CYCLE",
                        "移动后部门树会形成环：" + unitId + " 将出现在自己的祖先链上");
            }
            if (!visited.add(current)) {
                throw ApiException.unprocessable("ORG_UNIT_CYCLE", "现有部门树已经存在环：" + current);
            }
            current = parentLookup.apply(current).orElse(null);
        }
        if (depth >= MAX_DEPTH) {
            throw ApiException.unprocessable("ORG_UNIT_CYCLE", "部门层级超过安全上限，疑似存在环");
        }
    }
}
