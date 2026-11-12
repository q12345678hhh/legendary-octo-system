package com.acme.platform.organization;

import java.util.Set;
import java.util.UUID;

/**
 * 人员目录的可见范围：由授权决策解析后传入，查询层必须把它下推到 SQL。
 *
 * <p>视图人用于邮箱字段的「本人可见」判断；范围内其他成员的邮箱默认不返回。
 */
public record DirectoryScope(boolean tenantWide,
                             Set<UUID> personIds,
                             Set<UUID> orgUnitIds,
                             UUID viewerPersonId,
                             boolean emailVisible) {

    public DirectoryScope {
        personIds = personIds == null ? Set.of() : Set.copyOf(personIds);
        orgUnitIds = orgUnitIds == null ? Set.of() : Set.copyOf(orgUnitIds);
    }

    public static DirectoryScope tenant(UUID viewerPersonId, boolean emailVisible) {
        return new DirectoryScope(true, Set.of(), Set.of(), viewerPersonId, emailVisible);
    }

    public boolean empty() {
        return !tenantWide && personIds.isEmpty() && orgUnitIds.isEmpty();
    }

    public boolean includesPerson(UUID personId) {
        return tenantWide || (personId != null && personIds.contains(personId));
    }

    public boolean emailVisibleFor(UUID personId) {
        return emailVisible || (personId != null && personId.equals(viewerPersonId));
    }
}
