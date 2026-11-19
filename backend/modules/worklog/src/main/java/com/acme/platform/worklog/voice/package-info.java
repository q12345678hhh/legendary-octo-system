/**
 * 语音工时录入：录音上传、服务端转写与员工确认。
 *
 * <p>本包不接触任何设备端能力：音频只由员工在页面主动录音后上传，
 * 转写完成并确认生成工时后立即删除对象存储文件。
 */
@org.springframework.modulith.NamedInterface("voice")
package com.acme.platform.worklog.voice;
