package com.acme.platform.organization;

import java.time.LocalDate;

/**
 * 生效区间的统一判定：左闭右开。
 *
 * <p>关系实体直接带 effective_from/effective_to，不使用结构实体的变更单：
 * 未来生效的行可以提前落库，查询时按业务日期过滤，避免「定时任务没跑就没生效」。
 */
public final class EffectiveDating {

    private EffectiveDating() {
    }

    public static boolean isEffective(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || today == null) {
            return false;
        }
        return !from.isAfter(today) && (to == null || to.isAfter(today));
    }

    public static boolean overlaps(LocalDate fromA, LocalDate toA, LocalDate fromB, LocalDate toB) {
        LocalDate endA = toA == null ? LocalDate.MAX : toA;
        LocalDate endB = toB == null ? LocalDate.MAX : toB;
        return fromA.isBefore(endB) && fromB.isBefore(endA);
    }
}
