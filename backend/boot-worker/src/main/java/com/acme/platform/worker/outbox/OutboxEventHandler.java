package com.acme.platform.worker.outbox;

/**
 * 事件消费者。一个事件名只能有一个处理器，重复注册在启动期直接失败。
 */
public interface OutboxEventHandler {

    /**
     * 要消费的事件名，必须带主版本号，例如 {@code platform.smoke.ping.v1}。
     */
    String eventName();

    /**
     * 投递事件。抛出异常即视为本次投递失败，由调度器按退避策略重试。
     */
    void handle(OutboxEvent event) throws Exception;
}
