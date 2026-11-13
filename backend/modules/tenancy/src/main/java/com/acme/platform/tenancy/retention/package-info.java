/**
 * 删除、保留与数据主体请求的租户侧能力：回收站、法定留置、保留策略。
 *
 * <p>只通过 {@link com.acme.platform.tenancy.retention.RetentionService} 暴露给其他模块；
 * 业务模块实现 {@code com.acme.platform.platform.retention.RetentionTarget} 接入回收站。
 */
@org.springframework.modulith.NamedInterface("retention")
package com.acme.platform.tenancy.retention;
