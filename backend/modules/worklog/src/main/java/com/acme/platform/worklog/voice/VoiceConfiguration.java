package com.acme.platform.worklog.voice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 语音转写装配：配置全部为空时仍返回一个「未配置」的实现，由服务层转成 503。 */
@Configuration
@EnableConfigurationProperties(AsrProperties.class)
class VoiceConfiguration {

    @Bean
    SpeechTranscriber speechTranscriber(AsrProperties properties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleTranscriber(properties, objectMapper);
    }
}
