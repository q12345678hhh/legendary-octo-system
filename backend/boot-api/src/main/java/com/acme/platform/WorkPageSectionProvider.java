package com.acme.platform;

import java.util.UUID;

/**
 * 个人工作页的分区提供者。
 *
 * <p>每个分区独立鉴权与独立失败：某个分区不可用或加载失败时，其余分区仍应返回。
 * 返回 {@code null} 表示「无权或不适用」，该分区整体省略，不返回数量或摘要。
 */
public interface WorkPageSectionProvider {

    /** 分区键，如 time、report、tasks、files。 */
    String key();

    /** 读取分区数据；subject 是被查看的人，self 表示查看者就是本人。 */
    Object load(UUID subjectPersonId, boolean self);
}
