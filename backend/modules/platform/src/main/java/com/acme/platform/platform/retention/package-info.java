/**
 * 平台侧删除与保留能力：数据主体请求案件、删除墓碑与重放记录。
 *
 * <p>这些表是平台元数据（无 RLS）：只存主体哈希与判定结论，不存姓名、邮箱等正文。
 */
@org.springframework.modulith.NamedInterface("retention")
package com.acme.platform.platform.retention;
