package com.acme.platform.organization;

import com.acme.platform.platform.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 组织就绪度：老板角色必须先于业务模块启用。
 *
 * <p>老板/受托人是业务角色，和 identity_tenant_member 的 is_owner/is_admin 完全无关；
 * 这里只接收「当前生效」的雇佣 ID，避免把管理员身份混进业务判定。
 */
public record OrganizationReadiness(boolean ready, List<Blocker> blockers) {

    public enum Blocker {
        OWNER_ROLE_MISSING,
        TRUSTEE_MISSING,
        TRUSTEE_NOT_DISTINCT;

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Blocker fromWire(String value) {
            return Blocker.valueOf(value.trim().toUpperCase());
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }

    public static OrganizationReadiness evaluate(Set<UUID> ownerEmploymentIds,
                                                 Set<UUID> trusteeEmploymentIds) {
        Set<UUID> owners = ownerEmploymentIds == null ? Set.of() : ownerEmploymentIds;
        Set<UUID> trustees = trusteeEmploymentIds == null ? Set.of() : trusteeEmploymentIds;
        Set<Blocker> found = new LinkedHashSet<>();
        if (owners.isEmpty()) {
            found.add(Blocker.OWNER_ROLE_MISSING);
        }
        if (trustees.isEmpty()) {
            found.add(Blocker.TRUSTEE_MISSING);
        } else if (!owners.isEmpty() && trustees.stream().allMatch(owners::contains)) {
            found.add(Blocker.TRUSTEE_NOT_DISTINCT);
        }
        return new OrganizationReadiness(found.isEmpty(), new ArrayList<>(found));
    }

    /** 模块启用前调用：未就绪直接阻断，不留下「先开后补」的半成品配置。 */
    public void requireReady() {
        if (!ready) {
            throw ApiException.conflict("ORGANIZATION_NOT_READY",
                    "组织尚未就绪，缺少：" + blockers);
        }
    }
}
