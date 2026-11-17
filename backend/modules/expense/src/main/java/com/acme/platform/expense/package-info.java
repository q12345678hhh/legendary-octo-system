/**
 * 单据上传、OCR 抽取、规则预审与人工复核。
 *
 * <p>模块边界在本包声明：跨模块只能调用公开应用服务或订阅领域事件，禁止读取他模块内部包。
 *
 * <p>{@code platform :: file} 提供上传会话与归属校验，{@code platform :: storage}
 * 提供一次性的受限下载；OCR 与规则都在本模块内完成，不依赖其他业务模块。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "expense",
        allowedDependencies = { "platform", "platform :: file", "platform :: storage",
                "platform :: retention" })
package com.acme.platform.expense;
