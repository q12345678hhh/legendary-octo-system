/**
 * 作业子域对宿主应用（HTTP/Worker 适配层）暴露的契约。
 *
 * <p>命名接口只用于应用壳装配控制器与 Worker，不构成跨业务模块依赖。
 */
@org.springframework.modulith.NamedInterface("job")
package com.acme.platform.platform.job;
