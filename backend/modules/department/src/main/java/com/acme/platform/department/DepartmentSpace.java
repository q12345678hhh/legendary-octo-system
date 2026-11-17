package com.acme.platform.department;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 部门空间：与组织单元一一对应，只承载状态与名称。
 *
 * <p>成员、职位与汇报层级来自组织模块的实时投影，不在此记录。
 */
public record DepartmentSpace(UUID id,
                              UUID orgUnitId,
                              String name,
                              String status,
                              OffsetDateTime createdAt,
                              OffsetDateTime archivedAt) {
}
