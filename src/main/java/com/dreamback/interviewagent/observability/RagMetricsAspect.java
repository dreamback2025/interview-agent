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
 * RAG 检索耗时指标（含 embedding + 向量库查询 + 缓存命中/未命中）。
 *
 * <p>切 {@link com.dreamback.interviewagent.service.KnowledgeService#search}，
 * 指标 {@code rag_search_duration{result}} —— 失败也计时，便于发现向量库抖动。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RagMetricsAspect {

    private final MeterRegistry registry;

    @Around("execution(* com.dreamback.interviewagent.service.KnowledgeService.search(..))")
    public Object timeSearch(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        String result = "success";
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            result = "error";
            throw e;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Timer.builder("rag_search_duration")
                    .tag("result", result)
                    .register(registry)
                    .record(Duration.ofMillis(ms));
            log.debug("RAG 检索耗时: result={} {}ms", result, ms);
        }
    }
}
