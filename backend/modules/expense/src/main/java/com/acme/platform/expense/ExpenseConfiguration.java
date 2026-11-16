package com.acme.platform.expense;

import com.acme.platform.expense.ocr.DocumentExtractor;
import com.acme.platform.expense.ocr.OcrProperties;
import com.acme.platform.expense.ocr.OpenAiCompatibleOcrExtractor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 单据模块装配：OCR 适配器与规则引擎都在这里暴露，便于测试替换。
 */
@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class ExpenseConfiguration {

    @Bean
    public DocumentExtractor documentExtractor(OcrProperties properties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleOcrExtractor(properties, objectMapper);
    }

    @Bean
    public ExpenseRuleEngine expenseRuleEngine() {
        return new ExpenseRuleEngine();
    }
}
