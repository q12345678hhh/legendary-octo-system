package com.acme.platform.worklog.voice;

/**
 * 语音转写端口。
 *
 * <p>实现可以选择任意供应商，但必须满足两条产品约束：未配置时不得返回文本；
 * 返回的文本必须来自真实音频，不能由本地规则拼装。
 */
public interface SpeechTranscriber {

    boolean configured();

    Transcription transcribe(byte[] audio, String contentType);

    /**
     * @param text       转写文本
     * @param provider   供应商标识
     * @param model      模型标识
     * @param confidence 置信度，供应商不提供时为 null
     */
    record Transcription(String text, String provider, String model, Double confidence) {
    }
}
