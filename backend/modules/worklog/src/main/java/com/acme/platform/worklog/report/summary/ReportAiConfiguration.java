package com.acme.platform.worklog.report.summary;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 周报摘要装配：Bean 始终存在，未配置时不调用供应商，由服务降级。
 */
@Configuration
@EnableConfigurationProperties(ReportAiProperties.class)
class ReportAiConfiguration {

    @Bean
    ReportSummarizer reportSummarizer(ReportAiProperties properties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleSummarizer(properties, objectMapper);
    }

    @Bean
    ReportFactsBuilder reportFactsBuilder(ObjectMapper objectMapper) {
        return new ReportFactsBuilder(objectMapper);
    }

    @Bean
    ReportSummaryValidator reportSummaryValidator() {
        return new ReportSummaryValidator();
    }

    @Bean
    ReportSummaryGenerator reportSummaryGenerator(ReportSummarizer summarizer,
                                                  ReportSummaryValidator validator,
                                                  ReportAiProperties properties) {
        return new ReportSummaryGenerator(summarizer, validator, properties);
    }
}
