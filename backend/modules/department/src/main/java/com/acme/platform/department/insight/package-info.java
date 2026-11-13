/**
 * 管理洞察的公开接口：固定维度统计、阈值抑制、下钻与租户阈值设置。
 *
 * <p>供宿主应用（boot-api）的 {@code /api/v1/insights} 接口装配。
 */
@org.springframework.modulith.NamedInterface("insight")
package com.acme.platform.department.insight;
