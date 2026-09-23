package com.dreamback.interviewagent.config;

import com.dreamback.interviewagent.rag.HashingEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * embedding 选择：
 * - 默认（app.rag.embedding=ollama）：用 spring-ai-starter-model-ollama 提供的 EmbeddingModel（bge-m3, 1024 维）
 * - app.rag.embedding=stub：用哈希兜底模型，不需要 Ollama 即可验证 RAG 全链路
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.rag", name = "embedding", havingValue = "stub")
    public EmbeddingModel hashingEmbeddingModel(@Value("${app.rag.dimensions:1024}") int dimensions) {
        log.warn("使用哈希兜底 embedding（app.rag.embedding=stub，{} 维）：仅用于验证链路，"
                + "语义检索质量不如 bge-m3，正式使用请去掉该参数走 Ollama", dimensions);
        return new HashingEmbeddingModel(dimensions);
    }
}
