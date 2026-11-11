package com.acme.platform.organization;

import com.acme.platform.platform.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 汇报图规则：禁止自汇报、禁止成环。
 *
 * <p>实线与虚线一起参与判环：只查实线会漏掉「虚线绕一圈回到自己」的场景。
 * 虚线允许多个经理，所以这里从新经理向上做深度遍历，而不是假设每人只有一个上级。
 */
public final class ReportingRules {

    private static final int MAX_NODES = 5000;

    private ReportingRules() {
    }

    public static void ensureNoCycle(UUID subordinateId,
                                     UUID managerId,
                                     Function<UUID, List<UUID>> managersOf) {
        if (subordinateId.equals(managerId)) {
            throw ApiException.unprocessable("REPORTING_SELF", "员工不能向自己汇报");
        }
        Set<UUID> visited = new HashSet<>();
        java.util.ArrayDeque<UUID> queue = new java.util.ArrayDeque<>();
        queue.add(managerId);
        int inspected = 0;
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(subordinateId)) {
                throw ApiException.unprocessable("REPORTING_CYCLE",
                        "汇报关系会形成环：" + subordinateId + " 将成为自己的上级");
            }
            if (++inspected > MAX_NODES) {
                throw ApiException.unprocessable("REPORTING_CYCLE", "汇报图规模异常，疑似存在环");
            }
            for (UUID next : managersOf.apply(current)) {
                if (next != null && !visited.contains(next)) {
                    queue.addLast(next);
                }
            }
        }
    }
}
