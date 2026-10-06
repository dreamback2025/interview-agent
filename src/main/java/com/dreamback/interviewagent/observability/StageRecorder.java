package com.dreamback.interviewagent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 把一次业务流拆成若干段计时：既打一行日志（排查单次请求），也记 Micrometer Timer（看分布）。
 *
 * <p>为什么需要它：端到端耗时只能告诉你「慢」，不能告诉你「慢在哪」。本项目 analyze 一次
 * 要串起「读记录 / 知识库检索 / 记忆注入 / 模型调用 / 落库 / 覆盖度标注 / 写缓存」七件事，
 * 其中任何一段抖一下，端到端 P95 就跟着抖，但顶部指标看起来一模一样。
 *
 * <p>用法：
 * <pre>
 *   StageRecorder t = StageRecorder.start("analyze", registry);
 *   try {
 *       ...
 *       t.mark("load");   // mark 记的是「上一段」：从这个对象创建 / 上次 mark 到现在
 *       ...
 *   } finally {
 *       t.finish();       // 幂等，可重复调用
 *   }
 * </pre>
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>不做 AOP 自动切</b>：段边界是业务语义（比如「知识检索」是方法内部的一段），
 *       切点表达式表达不出来，硬要切就得把方法拆碎 —— 得不偿失。</li>
 *   <li><b>registry 可为 null</b>：单测与无监控环境里只打日志，不抛异常。</li>
 *   <li><b>日志用 INFO</b>：analyze 是低频高价值调用，一次一行；排查时直接 grep {@code [stage]}。</li>
 * </ul>
 */
@Slf4j
public final class StageRecorder {

    private static final double[] QUANTILES = {0.5, 0.95};

    private final MeterRegistry registry;
    private final String flow;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private final long startNanos;
    private long lastNanos;
    private boolean finished;

    private StageRecorder(String flow, MeterRegistry registry) {
        this.flow = flow;
        this.registry = registry;
        this.startNanos = System.nanoTime();
        this.lastNanos = startNanos;
    }

    public static StageRecorder start(String flow, MeterRegistry registry) {
        return new StageRecorder(flow, registry);
    }

    /**
     * 记下「刚结束的那一段」的耗时。段名允许重复（会累加），
     * 便于「同一段在循环里执行多次」的场景只用一个名字统计总量。
     */
    public void mark(String stage) {
        if (finished) {
            return;
        }
        long now = System.nanoTime();
        stages.merge(stage, now - lastNanos, Long::sum);
        lastNanos = now;
    }

    /** 收尾：记 total、把各段写成指标、打一行日志。重复调用只有第一次生效。 */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        long totalNanos = System.nanoTime() - startNanos;
        StringBuilder sb = new StringBuilder();
        stages.forEach((stage, nanos) -> {
            sb.append(stage).append('=').append(nanos / 1_000_000).append("ms ");
            record(stage, nanos);
        });
        log.info("[stage] flow={} total={}ms | {}",
                flow, totalNanos / 1_000_000, sb.toString().trim());
    }

    /** 各段耗时（纳秒），供单测断言使用 */
    public Map<String, Long> stages() {
        return Map.copyOf(stages);
    }

    private void record(String stage, long nanos) {
        if (registry == null) {
            return;
        }
        Timer.builder("stage.duration")
                .tag("flow", flow)
                .tag("stage", stage)
                // 与 llm_call_duration 同一套做法：客户端分位数，Prometheus 里直接读 quantile。
                // 段级别的 P95 才是「哪一段拖尾」的答案 —— 平均值会被大量快请求摊平。
                .publishPercentiles(QUANTILES)
                .register(registry)
                .record(Duration.ofNanos(nanos));
    }
}
