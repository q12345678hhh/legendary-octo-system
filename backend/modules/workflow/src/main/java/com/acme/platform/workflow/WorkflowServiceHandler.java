package com.acme.platform.workflow;

import java.util.UUID;

/** 服务任务处理器 SPI：按 {@code handler_key} 注册；未注册即视为配置错误。 */
public interface WorkflowServiceHandler {

    String key();

    void run(UUID instanceId, String nodeKey, String payloadJson);
}
