/**
 * 工时与周报。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 *
 * <p>{@code platform} 是共享技术底座（租户事务、请求上下文、Problem Details），
 * 工作助手设备链路需要用它把 RLS 上下文与写库绑在同一事务里。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "worklog",
        allowedDependencies = { "tenancy", "tenancy :: retention", "authorization", "department",
                "platform", "platform :: retention" })
package com.acme.platform.worklog;
