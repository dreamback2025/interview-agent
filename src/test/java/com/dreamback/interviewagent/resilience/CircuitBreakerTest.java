package com.dreamback.interviewagent.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dreamback.interviewagent.resilience.CircuitBreaker.State;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 熔断状态机测试。
 *
 * <p>时间用可控时钟注入，<b>全程不 sleep</b>：推进时间就是改一个 long。
 * 这样 9 个用例跑完不到 100ms，也不会因为机器卡顿而 flaky。
 */
class CircuitBreakerTest {

    /** 默认参数：4 个样本内失败率过半就开路，开路 30s，半开 2 个探测 */
    private static final AtomicLong NOW = new AtomicLong(1_000_000L);

    /** 连续失败路径默认关掉（MAX_VALUE 永远达不到），单独测失败率路径 */
    private static CircuitBreaker breaker() {
        return breaker(Integer.MAX_VALUE);
    }

    private static CircuitBreaker breaker(int consecutiveFailureLimit) {
        return new CircuitBreaker("t", 50, 4, consecutiveFailureLimit, 10_000L, 30_000L, 2,
                NOW::get, msg -> { });
    }

    private static void tick(long ms) {
        NOW.addAndGet(ms);
    }

    /** 让样本落到同一个桶里：桶宽 = 10000/10 = 1000ms */
    private static void fail(CircuitBreaker cb) {
        assertThatThrownBy(() -> cb.call(() -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 初始为关闭_正常调用全部放行() {
        CircuitBreaker cb = breaker();
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        for (int i = 0; i < 10; i++) {
            assertThat(cb.call(() -> "ok")).isEqualTo("ok");
        }
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        assertThat(cb.snapshot().failures()).isZero();
    }

    @Test
    void 样本不足时_即便全失败也不开路() {
        CircuitBreaker cb = breaker();
        // min-samples=4：只失败 3 次不该开路，否则一次抖动就会切断正常的上游
        fail(cb);
        fail(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        assertThat(cb.snapshot().failureRatePercent()).isEqualTo(100);
    }

    @Test
    void 达到样本且失败率过阈值_开路() {
        CircuitBreaker cb = breaker();
        cb.call(() -> "ok");        // 样本 1，失败 0
        fail(cb);                    // 2 / 1
        fail(cb);                    // 3 / 2 —— 样本不足 4，仍不开路
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        fail(cb);                    // 4 / 3 → 75% ≥ 50%，且样本够了
        assertThat(cb.state()).isEqualTo(State.OPEN);
    }

    @Test
    void 开路后调用直接失败_且不执行受保护的逻辑() {
        CircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) {
            fail(cb);
        }
        assertThat(cb.state()).isEqualTo(State.OPEN);

        List<String> executed = new ArrayList<>();
        assertThatThrownBy(() -> cb.call(() -> {
            executed.add("ran");
            return "ok";
        })).isInstanceOf(CircuitOpenException.class);
        assertThat(executed).isEmpty();   // 关键：上游一次都没被调用
    }

    @Test
    void 等待结束后进入半开_只放行限定个数的探测() {
        CircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) {
            fail(cb);
        }
        tick(30_000L);
        // 第一次调用（成功）进入半开并占用 1 个探测名额
        assertThat(cb.call(() -> "ok")).isEqualTo("ok");
        assertThat(cb.state()).isEqualTo(State.HALF_OPEN);
        // 第二个名额
        assertThat(cb.call(() -> "ok")).isEqualTo("ok");
        assertThat(cb.state()).isEqualTo(State.CLOSED);   // 2 个探测全成功 → 关闭
    }

    @Test
    void 半开探测失败_立刻回到开路并重新计时() {
        CircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) {
            fail(cb);
        }
        tick(30_000L);
        fail(cb);                            // 探测失败
        assertThat(cb.state()).isEqualTo(State.OPEN);
        assertThat(cb.retryAfterMs()).isEqualTo(30_000L);   // 重新计时，不是继续往下走

        tick(10_000L);
        assertThat(cb.retryAfterMs()).isEqualTo(20_000L);
        assertThatThrownBy(() -> cb.call(() -> "ok")).isInstanceOf(CircuitOpenException.class);
    }

    @Test
    void 半开迟迟没有探测请求_会超时回到开路() {
        CircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) {
            fail(cb);
        }
        tick(30_000L);
        cb.call(() -> "ok");                 // 进入半开，只用了 1 个名额
        assertThat(cb.state()).isEqualTo(State.HALF_OPEN);
        tick(30_001L);                       // 一直没人来
        assertThatThrownBy(() -> cb.call(() -> "ok")).isInstanceOf(CircuitOpenException.class);
        assertThat(cb.state()).isEqualTo(State.OPEN);
    }

    @Test
    void 失败记录会随窗口滑出_不会永久记账() {
        CircuitBreaker cb = breaker();
        fail(cb);
        fail(cb);
        fail(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(State.OPEN);

        tick(60_000L);                       // 远超窗口长度 10s
        // 窗口已滑出：这次成功不会被旧账拖累
        assertThat(cb.call(() -> "ok")).isEqualTo("ok");
        assertThat(cb.snapshot().total()).isEqualTo(1);
        assertThat(cb.snapshot().failures()).isZero();
    }

    @Test
    void 关闭状态下失败率未达阈值_保持关闭() {
        CircuitBreaker cb = breaker();
        cb.call(() -> "ok");
        cb.call(() -> "ok");
        cb.call(() -> "ok");
        fail(cb);                    // 4 / 1 = 25%
        fail(cb);                    // 5 / 2 = 40%
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        assertThat(cb.snapshot().failureRatePercent()).isEqualTo(40);
    }

    /**
     * 这是实测踩出来的场景，也是「连续失败」这一路存在的理由。
     *
     * <p>上游不可达时，每次调用要等满超时才失败；如果单次耗时 ≥ 窗口长度，
     * 下一次调用时整个滑动窗口已被清空 —— total 永远是 1，失败率永远判不了，
     * 只靠失败率的熔断器在这种情况下<b>完全失效</b>。
     */
    @Test
    void 慢失败场景_窗口被滑空时靠连续失败开路() {
        CircuitBreaker cb = breaker(3);
        fail(cb);
        tick(61_000L);                       // 模拟「等满 60s 超时」，远超窗口 10s
        fail(cb);
        tick(61_000L);
        // 窗口已被滑空：即便连续失败了两次，样本数也攒不起来
        assertThat(cb.snapshot().total()).isZero();
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        fail(cb);                             // 连续第 3 次
        assertThat(cb.state()).isEqualTo(State.OPEN);
    }

    @Test
    void 成功一次即清零连续失败计数() {
        // minSamples 设为 MAX 关掉失败率路径，只观察连续失败这一路
        CircuitBreaker cb = new CircuitBreaker("t", 50, Integer.MAX_VALUE, 3,
                10_000L, 30_000L, 2, NOW::get, msg -> { });
        fail(cb);
        fail(cb);
        cb.call(() -> "ok");                 // 清零
        fail(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(State.CLOSED);   // 只连续 2 次，不该开路
        fail(cb);
        assertThat(cb.state()).isEqualTo(State.OPEN);
    }

    @Test
    void 恢复后统计重新清零() {
        CircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) {
            fail(cb);
        }
        tick(30_000L);
        cb.call(() -> "ok");
        cb.call(() -> "ok");
        assertThat(cb.state()).isEqualTo(State.CLOSED);
        CircuitBreaker.Snapshot s = cb.snapshot();
        assertThat(s.failures()).isZero();
        assertThat(s.retryAfterMs()).isZero();
    }
}
