package com.acme.platform.platform.retention;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 可进回收站的数据类实现端口。
 *
 * <p>放在 platform 模块而不是 tenancy：platform 与 worklog 都要实现它，
 * 而 tenancy 依赖 platform，接口放 tenancy 会造成模块循环依赖。
 * 实现方负责自身行状态修改与对象存储清理；回收站只保存元数据快照。
 */
public interface RetentionTarget {

    /** 数据类标识，必须与 {@code retention_bin_item.data_class} 一致。 */
    String dataClass();

    /** 删除时刻的元数据快照；返回空表示对象不存在或不属于当前租户。 */
    Optional<Map<String, Object>> snapshot(UUID objectId);

    /** 标记为已回收（不出现在业务列表），但保留数据行与对象。 */
    void markRecycled(UUID objectId);

    /** 从回收站恢复业务可见性。 */
    void restore(UUID objectId);

    /** 物理清理：删除数据行与对象存储对象。 */
    void purge(UUID objectId);
}
