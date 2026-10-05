package com.dreamback.interviewagent.config;

import com.dreamback.interviewagent.llm.DeepSeekLlmService;
import com.dreamback.interviewagent.llm.LlmService;
import com.dreamback.interviewagent.llm.StubLlmService;
import com.dreamback.interviewagent.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
public class LlmConfig {

    /**
     * 上游熔断器。
     *
     * <p>为什么需要：超时只解决「单次调用最多等多久」，但上游故障的典型表现是
     * 所有请求都慢 —— 每个请求仍会占满 60s 才释放，用户重试又把新请求灌进来，
     * 最后所有槽位都在等一个已经死了的上游。熔断管的是「这段时间干脆别调」。
     *
     * <p>默认参数按「宁可晚开、不可误开」取：5 个样本、失败率过半才开路，
     * 避免一两次偶发抖动就把正常的上游切断。
     */
    @Bean
    public CircuitBreaker llmCircuitBreaker(MeterRegistry registry,
                                            @Value("${app.llm.circuit.enabled:true}") boolean enabled,
                                            @Value("${app.llm.circuit.failure-rate:50}") int failureRate,
                                            @Value("${app.llm.circuit.min-samples:5}") int minSamples,
                                            @Value("${app.llm.circuit.consecutive-failures:3}") int consecutiveFailures,
                                            @Value("${app.llm.circuit.window-ms:300000}") long windowMs,
                                            @Value("${app.llm.circuit.open-wait-ms:30000}") long openWaitMs,
                                            @Value("${app.llm.circuit.half-open-permits:2}") int halfOpenPermits) {
        CircuitBreaker breaker = enabled
                ? new CircuitBreaker("llm", failureRate, minSamples, consecutiveFailures,
                        windowMs, openWaitMs, halfOpenPermits,
                        System::currentTimeMillis, msg -> log.warn("熔断状态变化：{}", msg))
                : null;
        if (breaker == null) {
            log.info("上游熔断 = 关闭");
            // 阈值 101% + 样本数 MAX：两个条件都不可能满足，等价于永久关闭（避免到处判空）
            return new CircuitBreaker("llm-disabled", 101, Integer.MAX_VALUE, windowMs, openWaitMs, 1);
        }
        // 0=CLOSED 1=OPEN 2=HALF_OPEN：告警直接对 state==1 建规则
        Gauge.builder("llm_circuit_state", breaker, b -> b.state().ordinal())
                .description("上游熔断状态：0=关闭(正常) 1=开路(熔断中) 2=半开(探测中)")
                .register(registry);
        log.info("上游熔断 = 开启（失败率>{}% 且样本≥{}，或连续失败{}次；窗口{}ms，开路{}ms，半开探测{}个）",
                failureRate, minSamples, consecutiveFailures, windowMs, openWaitMs, halfOpenPermits);
        return breaker;
    }

    @Bean
    public LlmService llmService(ChatClient.Builder chatClientBuilder,
                                 CircuitBreaker llmCircuitBreaker,
                                 @Value("${spring.ai.openai.api-key:}") String apiKey,
                                 @Value("${spring.ai.openai.chat.options.model:}") String model,
                                 @Value("${app.llm.stub:false}") boolean forceStub,
                                 @Value("${app.llm.timeout-ms:60000}") long timeoutMs,
                                 @Value("${app.llm.circuit.fallback-heuristic:false}") boolean fallbackHeuristic) {
        boolean hasKey = StringUtils.hasText(apiKey) && !apiKey.startsWith("sk-placeholder");
        if (forceStub || !hasKey) {
            // 注意：环境变量被设置成空字符串时，Spring 不会回退到默认占位符，同样会走到这里
            log.warn("LLM 模式 = stub（原因：{}）。除 LLM 外的链路可用；" +
                            "配置 DEEPSEEK_API_KEY 或写入 application.yml 后【重启服务】才会生效。",
                    forceStub ? "app.llm.stub=true" : "未检测到可用 Key（为空或仍是占位符）");
            return new StubLlmService();
        }
        log.info("LLM 模式 = deepseek（model={}, timeoutMs={}, 熔断降级={}）",
                model, timeoutMs, fallbackHeuristic);
        return new DeepSeekLlmService(chatClientBuilder.build(), timeoutMs,
                llmCircuitBreaker, fallbackHeuristic);
    }
}
