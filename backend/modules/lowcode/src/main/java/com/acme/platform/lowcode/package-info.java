/**
 * 低代码类型化动态字段。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "lowcode",
        allowedDependencies = { "tenancy", "authorization", "appcatalog" })
package com.acme.platform.lowcode;
