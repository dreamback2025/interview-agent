package com.dreamback.interviewagent.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StageRecorderTest {

    private static void nap(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void 各段耗时之和不超过总耗时() {
        StageRecorder t = StageRecorder.start("analyze", new SimpleMeterRegistry());
        nap(20);
        t.mark("load");
        nap(40);
        t.mark("llm");
        t.finish();

        Map<String, Long> s = t.stages();
        assertTrue(s.get("load") >= 15_000_000, "load 段应约 20ms，实际 " + s.get("load"));
        assertTrue(s.get("llm") >= 35_000_000, "llm 段应约 40ms，实际 " + s.get("llm"));
        // 各段只覆盖「起点到最后一次 mark」，总耗时还含最后一段之后的时间，故只能是 <=
        assertTrue(s.get("load") + s.get("llm") <= 200_000_000);
    }

    @Test
    void 同名段累加而不是覆盖() {
        StageRecorder t = StageRecorder.start("flow", null);
        nap(10);
        t.mark("coverage");
        nap(10);
        t.mark("coverage");
        t.finish();

        assertEquals(1, t.stages().size(), "同名段应合并为一条");
        assertTrue(t.stages().get("coverage") >= 18_000_000);
    }

    @Test
    void finish可重复调用且不重复记指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StageRecorder t = StageRecorder.start("flow", registry);
        t.mark("a");
        t.finish();
        t.finish();

        // 幂等的意义：调用方既在业务分支里 finish，又在 finally 里 finish 时，指标不会被记两遍
        assertEquals(1, registry.find("stage.duration").timers().size());
        assertEquals(1.0, registry.find("stage.duration").timer().count());
    }

    @Test
    void finish之后mark不再生效() {
        StageRecorder t = StageRecorder.start("flow", null);
        t.mark("a");
        t.finish();
        t.mark("b");

        assertEquals(1, t.stages().size(), "finish 之后的 mark 必须被忽略，否则会污染统计");
    }

    @Test
    void registry为空时只打日志不抛异常() {
        StageRecorder t = StageRecorder.start("flow", null);
        t.mark("a");
        t.finish();

        assertTrue(t.stages().containsKey("a"));
    }

    @Test
    void 指标带flow与stage标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StageRecorder t = StageRecorder.start("analyze", registry);
        t.mark("llm");
        t.finish();

        var timer = registry.find("stage.duration").tag("stage", "llm").timer();
        assertTrue(timer != null);
        assertEquals("analyze", timer.getId().getTag("flow"));
        // 客户端分位数已声明：Prometheus 里能直接读 quantile="0.95"，不用手写 histogram_quantile
        assertTrue(timer.takeSnapshot().percentileValues().length >= 2);
    }
}
