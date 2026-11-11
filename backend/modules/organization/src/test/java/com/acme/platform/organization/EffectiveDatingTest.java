package com.acme.platform.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 生效区间左闭右开：结束日当天已经不再生效，交接不会出现两条同时生效。 */
class EffectiveDatingTest {

    private static final LocalDate D1 = LocalDate.parse("2026-01-01");
    private static final LocalDate D2 = LocalDate.parse("2026-02-01");

    @Test
    void effectiveWindowIsLeftClosedRightOpen() {
        assertThat(EffectiveDating.isEffective(D1, D2, D1)).isTrue();
        assertThat(EffectiveDating.isEffective(D1, D2, D2.minusDays(1))).isTrue();
        assertThat(EffectiveDating.isEffective(D1, D2, D2)).isFalse();
        assertThat(EffectiveDating.isEffective(D1, null, LocalDate.parse("2099-01-01"))).isTrue();
        assertThat(EffectiveDating.isEffective(D1, D2, D1.minusDays(1))).isFalse();
        assertThat(EffectiveDating.isEffective(null, D2, D1)).isFalse();
    }

    @Test
    void adjacentPeriodsDoNotOverlap() {
        assertThat(EffectiveDating.overlaps(D1, D2, D2, null)).isFalse();
        assertThat(EffectiveDating.overlaps(D1, D2, D2.minusDays(1), D2.plusDays(1))).isTrue();
    }

    @Test
    void openEndedPeriodOverlapsEverythingAfterStart() {
        assertThat(EffectiveDating.overlaps(D1, null, D2, D2.plusDays(10))).isTrue();
        assertThat(EffectiveDating.overlaps(D2, D2.plusDays(10), D1, D2)).isFalse();
    }
}
