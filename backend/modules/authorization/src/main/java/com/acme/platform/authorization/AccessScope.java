package com.acme.platform.authorization;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 已解析的数据范围：只包含「可见的人、部门、法人」。
 *
 * <p>列表、搜索、导出与 AI 检索都必须把它下推到 SQL，禁止先查全量再在内存里过滤。
 */
public record AccessScope(boolean tenantWide,
                          boolean selfCreated,
                          Set<UUID> personIds,
                          Set<UUID> orgUnitIds,
                          Set<UUID> legalEntityIds) {

    public AccessScope {
        personIds = personIds == null ? Set.of() : Set.copyOf(personIds);
        orgUnitIds = orgUnitIds == null ? Set.of() : Set.copyOf(orgUnitIds);
        legalEntityIds = legalEntityIds == null ? Set.of() : Set.copyOf(legalEntityIds);
    }

    public static AccessScope none() {
        return new AccessScope(false, false, Set.of(), Set.of(), Set.of());
    }

    public static AccessScope tenant() {
        return new AccessScope(true, false, Set.of(), Set.of(), Set.of());
    }

    public static AccessScope self(UUID personId) {
        return new AccessScope(false, false,
                personId == null ? Set.of() : Set.of(personId), Set.of(), Set.of());
    }

    public static AccessScope units(Set<UUID> unitIds) {
        return new AccessScope(false, false, Set.of(),
                unitIds == null ? Set.of() : unitIds, Set.of());
    }

    public AccessScope merge(AccessScope other) {
        if (other == null) {
            return this;
        }
        if (tenantWide || other.tenantWide) {
            return new AccessScope(true, selfCreated || other.selfCreated,
                    Set.of(), Set.of(), Set.of());
        }
        Set<UUID> persons = new HashSet<>(personIds);
        persons.addAll(other.personIds);
        Set<UUID> units = new HashSet<>(orgUnitIds);
        units.addAll(other.orgUnitIds);
        Set<UUID> entities = new HashSet<>(legalEntityIds);
        entities.addAll(other.legalEntityIds);
        return new AccessScope(false, selfCreated || other.selfCreated, persons, units, entities);
    }

    public boolean isEmpty() {
        return !tenantWide && !selfCreated && personIds.isEmpty() && orgUnitIds.isEmpty()
                && legalEntityIds.isEmpty();
    }

    public boolean includesPerson(UUID personId) {
        return tenantWide || (personId != null && personIds.contains(personId));
    }

    public boolean includesOrgUnit(UUID orgUnitId) {
        return tenantWide || (orgUnitId != null && orgUnitIds.contains(orgUnitId));
    }

    public boolean includesLegalEntity(UUID legalEntityId) {
        return tenantWide || (legalEntityId != null && legalEntityIds.contains(legalEntityId));
    }
}
