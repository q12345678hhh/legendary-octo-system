/**
 * 目标与关键结果：个人目标和部门团队目标共用一套存储，团队进度由关键结果派生。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "goal",
        allowedDependencies = { "platform" })
package com.acme.platform.goal;
