package com.dreamback.interviewagent.config;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.rag.CachingEmbeddingModel;
import com.dreamback.interviewagent.rag.HashingEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

/**
 * embedding 选择 + 缓存包装：
 * - 默认（app.rag.embedding=ollama）：用 spring-ai-starter-model-ollama 提供的 EmbeddingModel（bge-m3, 1024 维）
 * - app.rag.embedding=stub：用哈希兜底模型，不需要 Ollama 即可验证 RAG 全链路
 *
 * <p>缓存通过 {@link BeanPostProcessor} 包装，而不是在 {@code vectorStore()} 里手写：
 * 这样任何注入 EmbeddingModel 的地方都自动拿到带缓存的版本，调用方（PgVectorStore）
 * 完全不知道自己被缓存了 —— 缓存策略与业务逻辑解耦。
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

    /**
     * 静态 @Bean：BeanPostProcessor 必须尽早实例化，静态方法可以避免为了创建它
     * 而提前实例化整个配置类（否则可能触发其它 bean 的过早初始化）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.rag.embedding-cache", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public static EmbeddingCachePostProcessor embeddingCachePostProcessor(
            ObjectProvider<CacheService> cacheProvider,
            ObjectProvider<MeterRegistry> registryProvider,
            @Value("${app.rag.embedding-cache.max-entries:2000}") int maxEntries,
            @Value("${app.rag.embedding-cache.redis:true}") boolean redis,
            @Value("${app.rag.embedding-cache.ttl:7d}") Duration ttl,
            @Value("${app.rag.embedding-cache.model-tag:}") String modelTag,
            @Value("${app.rag.embedding:ollama}") String source,
            @Value("${spring.ai.ollama.embedding.options.model:}") String ollamaModel,
            @Value("${app.rag.dimensions:1024}") int dimensions) {
        // 模型标识进缓存 key：换模型后向量空间变了，不换 key 会拿旧向量比新向量，
        // 检索结果全错且不抛异常 —— 这类故障极难定位，所以宁可让缓存整体失效
        String tag = (modelTag == null || modelTag.isBlank())
                ? source + ":" + ollamaModel + ":" + dimensions
                : modelTag;
        return new EmbeddingCachePostProcessor(tag, maxEntries, redis,
                cacheProvider::getIfAvailable, ttl, registryProvider::getIfAvailable);
    }

    /** 包装所有 EmbeddingModel bean，并保留引用供 /api/debug 观测命中率。 */
    public static class EmbeddingCachePostProcessor implements BeanPostProcessor, Ordered {

        private final String modelTag;
        private final int maxEntries;
        private final boolean redis;
        private final java.util.function.Supplier<CacheService> cacheProvider;
        private final Duration ttl;
        private final java.util.function.Supplier<MeterRegistry> registryProvider;

        /** 实际被包出来的实例（通常 1 个；stub + ollama 并存时会是 2 个） */
        private final List<CachingEmbeddingModel> wrapped = new CopyOnWriteArrayList<>();

        EmbeddingCachePostProcessor(String modelTag,
                                    int maxEntries,
                                    boolean redis,
                                    java.util.function.Supplier<CacheService> cacheProvider,
                                    Duration ttl,
                                    java.util.function.Supplier<MeterRegistry> registryProvider) {
            this.modelTag = modelTag;
            this.maxEntries = maxEntries;
            this.redis = redis;
            this.cacheProvider = cacheProvider;
            this.ttl = ttl;
            this.registryProvider = registryProvider;
            log.info("embedding 缓存已启用：modelTag={}，L1 上限 {} 条，L2(Redis)={}，ttl={}",
                    modelTag, maxEntries, redis, ttl);
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            // 只包一层，避免重复包装
            if (bean instanceof EmbeddingModel model && !(bean instanceof CachingEmbeddingModel)) {
                CachingEmbeddingModel cached = new CachingEmbeddingModel(
                        model, modelTag, maxEntries, redis, cacheProvider, ttl, registryProvider);
                wrapped.add(cached);
                return cached;
            }
            return bean;
        }

        /** 最低优先级：让别人（@Lazy、代理等）先处理完，最后再包缓存 */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        public List<CachingEmbeddingModel.Stats> stats() {
            return wrapped.stream().map(CachingEmbeddingModel::stats).toList();
        }

        public boolean isEmpty() {
            return wrapped.isEmpty();
        }
    }
}
