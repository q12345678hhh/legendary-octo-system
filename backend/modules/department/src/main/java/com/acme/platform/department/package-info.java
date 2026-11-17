/**
 * 部门工作区与可见范围。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 *
 * <p>{@code platform} 是共享技术底座（租户事务、请求上下文、Problem Details），
 * 部门空间的读写必须把 RLS 上下文与写库绑在同一事务里。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "department",
        allowedDependencies = { "tenancy", "organization", "authorization", "platform" })
package com.acme.platform.department;
