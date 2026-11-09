/**
 * 插件协议与执行编排。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "plugin",
        allowedDependencies = { "tenancy", "authorization" })
package com.acme.platform.plugin;
