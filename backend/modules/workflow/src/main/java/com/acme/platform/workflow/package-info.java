/**
 * 工作流与审批。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 * 审批人解析经 {@code organization} 的公开只读查询；业务回调由 boot-api 实现
 * {@code WorkflowBusinessHandler}，避免 workflow 反向依赖业务模块。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "workflow",
        allowedDependencies = { "tenancy", "authorization", "organization", "platform" })
package com.acme.platform.workflow;
