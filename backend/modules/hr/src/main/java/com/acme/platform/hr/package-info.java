/**
 * HR 域（独立高敏）：不与业务工作域共享数据。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "hr",
        allowedDependencies = { "tenancy", "organization", "authorization" })
package com.acme.platform.hr;
