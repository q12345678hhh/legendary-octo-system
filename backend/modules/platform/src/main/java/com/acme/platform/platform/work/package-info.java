/**
 * 工作状态只读端口：部门工作区、洞察等模块通过它读取工时状态，
 * 不直接依赖 worklog 的内部表。
 */
@org.springframework.modulith.NamedInterface("work")
package com.acme.platform.platform.work;
