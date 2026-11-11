package com.acme.platform.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 汇报图禁止自汇报与成环；实线与虚线一起参与判环。 */
class ReportingGraphTest {

    private static final UUID BOSS = uuid(1);
    private static final UUID LEAD = uuid(2);
    private static final UUID MEMBER = uuid(3);

    @Test
    void selfReportingIsRejected() {
        assertThatThrownBy(() -> ReportingRules.ensureNoCycle(MEMBER, MEMBER, id -> List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORTING_SELF"));
    }

    @Test
    void solidCycleIsRejectedAcrossThreeLevels() {
        Map<UUID, List<UUID>> managers = Map.of(
                LEAD, List.of(BOSS),
                MEMBER, List.of(LEAD));
        assertThatThrownBy(() -> ReportingRules.ensureNoCycle(BOSS, MEMBER,
                id -> managers.getOrDefault(id, List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORTING_CYCLE"));
    }

    @Test
    void dottedLineAlsoParticipatesInCycleCheck() {
        Map<UUID, List<UUID>> managers = Map.of(
                MEMBER, List.of(LEAD, BOSS));
        assertThatThrownBy(() -> ReportingRules.ensureNoCycle(LEAD, MEMBER,
                id -> managers.getOrDefault(id, List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORTING_CYCLE"));
    }

    @Test
    void multipleManagersWithoutCycleIsAllowed() {
        Map<UUID, List<UUID>> managers = Map.of(
                LEAD, List.of(BOSS),
                MEMBER, List.of(BOSS));
        assertThatCode(() -> ReportingRules.ensureNoCycle(MEMBER, LEAD,
                id -> managers.getOrDefault(id, List.of())))
                .doesNotThrowAnyException();
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
    }
}
