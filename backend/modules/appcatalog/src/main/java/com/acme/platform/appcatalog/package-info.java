/**
 * 应用目录与模块治理。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "appcatalog",
        allowedDependencies = { "tenancy", "authorization", "platform" })
package com.acme.platform.appcatalog;
