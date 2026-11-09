/**
 * 部门工作区与可见范围。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "department",
        allowedDependencies = { "tenancy", "organization", "authorization" })
package com.acme.platform.department;
