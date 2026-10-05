package com.dreamback.interviewagent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * LLM 调用计时与结果计数。
 *
 * <p>切 {@link com.dreamback.interviewagent.llm.LlmService} 的同步方法（chat / structured），
 * 不切 stream（Flux 异步，计时点不同）。指标：
 * <ul>
 *   <li>{@code llm_call_duration{method,result}} —— 调用耗时分布（直方图，P50/P95 可查）</li>
 * </ul>
 *
 * <p>不直接改 DeepSeekLlmService：它是 @Bean new 出来的，注入 MeterRegistry 要改构造；
 * 用 AOP 切接口，impl 无感知，未来加新实现也自动被监控。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LlmMetricsAspect {

    private final MeterRegistry registry;

    @Around("execution(* com.dreamback.interviewagent.llm.LlmService.chat(..)) || "
            + "execution(* com.dreamback.interviewagent.llm.LlmService.structured(..))")
    public Object timeLlm(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        String method = pjp.getSignature().getName();
        String result = "success";
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            result = "error";
            throw e;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Timer.builder("llm_call_duration")
                    .tag("method", method)
                    .tag("result", result)
                    // 客户端分位数：Prometheus 里直接出 quantile="0.95"，排查时不用手写 histogram_quantile。
                    // ⚠️ 局限：客户端分位数不可跨实例聚合 —— 多实例部署要用 le 分桶
                    //    （application.yml 里 percentiles-histogram 已开）+ histogram_quantile()。
                    //    实测过：只靠 management.metrics.distribution.percentiles 配置不生效，
                    //    必须在 builder 上显式声明。
                    .publishPercentiles(0.5, 0.95)
                    .register(registry)
                    .record(Duration.ofMillis(ms));
            log.debug("LLM 调用耗时: method={} result={} {}ms", method, result, ms);
        }
    }
}
