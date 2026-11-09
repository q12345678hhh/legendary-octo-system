/**
 * 多租户上下文与租户解析：为所有写操作提供租户边界。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "tenancy")
package com.acme.platform.tenancy;
