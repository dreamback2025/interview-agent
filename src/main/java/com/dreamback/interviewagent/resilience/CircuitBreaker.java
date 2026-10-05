package com.dreamback.interviewagent.resilience;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 极简滑动窗口熔断器。
 *
 * <p>为什么自己写而不引 Resilience4j：这里只需要「失败率 → 开路 → 半开探测 → 恢复」
 * 这一条主链路，约 150 行就能写清楚；引一个依赖反而要解释它的配置模型。
 * 自己写的好处是每个状态跃迁都能在单测里逐步验证（见 CircuitBreakerTest）。
 *
 * <h3>它解决什么问题</h3>
 * 上游（DeepSeek）出故障时的典型表现是「变慢」而不是「报错」—— 请求发出去，对端不响应。
 * 这时每个请求都要占住线程、连接，直到 60s 超时才释放。用户会疯狂重试，
 * 于是所有槽位都被「等一个已经死了的上游」占满，<b>系统从「慢」退化成「死」</b>。
 *
 * 熔断器的作用是：一旦判定上游不可用，<b>后续请求不再发出</b>，立刻失败并快速返回。
 * 它和超时是互补的 —— 超时管「单次调用最多等多久」，熔断管「这段时间干脆别调」。
 *
 * <h3>状态机</h3>
 * <pre>
 *   CLOSED ──(窗口内失败率超阈值 且 样本数够)──▶ OPEN
 *     ▲                                          │
 *     │                                    (等待 openWaitMs)
 *     │                                          ▼
 *     └──(探测全部成功)── HALF_OPEN ◀────────────┘
 *                            │
 *                       (任一失败) ──▶ OPEN（重新计时）
 * </pre>
 *
 * <p>两个容易漏掉的设计点：
 * <ol>
 *   <li><b>最小样本数</b>：只调了 1 次且失败，失败率就是 100%，但不能因此开路 ——
 *       样本太少说明不了问题。必须 {@code total >= minSamples} 才允许判定。</li>
 *   <li><b>半开限流</b>：OPEN 转 HALF_OPEN 后不能放行全部流量，否则上游刚恢复
 *       就被瞬间打垮（雪崩）。只放 {@code halfOpenPermits} 个探测请求。</li>
 * </ol>
 *
 * <h3>为什么还要「连续失败」这一路（实测踩出来的坑）</h3>
 * 只用失败率是不够的。本项目实测过：上游不可达时每次调用要等满 60s 超时才失败，
 * 而窗口长度也是 60s —— <b>下一次调用时整个窗口已经被滑空</b>，total 永远是 1，
 * 永远达不到最小样本数，失败率统计彻底失效，熔断器形同虚设（实测连发 4 次，
 * 4 次全部各等 61s，一次都没开路）。
 *
 * <p>根因是：<b>失败率依赖「窗口里攒得下多个请求」，而当单次耗时 ≥ 窗口长度时，
 * 这个前提不成立。</b>慢失败恰恰是最需要熔断的场景。
 * 所以额外加一路「连续失败计数」：它不受窗口滑动影响，
 * 连续 {@code consecutiveFailures} 次失败就开路，成功一次即清零。
 * 两路取「或」—— 任一路判定成立即开路。
 *
 * <h3>并发</h3>
 * 状态跃迁与窗口计数都在 {@code synchronized} 内完成。调用频率是「每请求一两次」量级，
 * 锁竞争可忽略；换来的是不需要处理无锁数据结构的可见性问题。
 *
 * <p>时间用 {@link LongSupplier} 注入，单测可以不 sleep 就推进时间。
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** 滑动窗口切成几个桶。桶越多统计越平滑，代价是内存与遍历 */
    private static final int BUCKETS = 10;

    private final String name;
    /** 失败率阈值（百分比，0~100） */
    private final int failureRateThreshold;
    /** 判定所需的最小样本数 —— 防止「第一次失败就开路」 */
    private final int minSamples;
    /** 连续失败多少次就开路的上限。与失败率并列，专治「单次耗时 ≥ 窗口长度」的慢失败 */
    private final int consecutiveFailureLimit;
    private final long windowMs;
    /** OPEN 后等多久才放探测请求 */
    private final long openWaitMs;
    /** HALF_OPEN 期间允许几个探测请求 */
    private final int halfOpenPermits;

    private final LongSupplier clock;
    private final Consumer<String> onStateChange;

    private final long bucketMs;
    private final int[] totals = new int[BUCKETS];
    private final int[] failures = new int[BUCKETS];
    private long currentBucket;

    private State state = State.CLOSED;
    private long openedAt;
    /** 进入 HALF_OPEN 的时刻 —— 半开同样需要超时，否则会一直挂在这个状态 */
    private long halfOpenAt;
    private int halfOpenAttempts;
    private int halfOpenSuccess;
    /** 连续失败计数：成功一次即清零，不受滑动窗口影响 */
    private int consecutiveFailures;

    public CircuitBreaker(String name,
                          int failureRateThreshold,
                          int minSamples,
                          long windowMs,
                          long openWaitMs,
                          int halfOpenPermits) {
        this(name, failureRateThreshold, minSamples, 3, windowMs, openWaitMs, halfOpenPermits,
                System::currentTimeMillis, s -> { });
    }

    public CircuitBreaker(String name,
                          int failureRateThreshold,
                          int minSamples,
                          int consecutiveFailureLimit,
                          long windowMs,
                          long openWaitMs,
                          int halfOpenPermits,
                          LongSupplier clock,
                          Consumer<String> onStateChange) {
        this.name = name;
        this.failureRateThreshold = failureRateThreshold;
        this.minSamples = minSamples;
        this.consecutiveFailureLimit = consecutiveFailureLimit;
        this.windowMs = windowMs;
        this.openWaitMs = openWaitMs;
        this.halfOpenPermits = halfOpenPermits;
        this.clock = clock;
        this.onStateChange = onStateChange;
        this.bucketMs = Math.max(1L, windowMs / BUCKETS);
        this.currentBucket = clock.getAsLong() / bucketMs;
    }

    /** 当前状态（供指标与调试端点读取） */
    public synchronized State state() {
        return state;
    }

    /** OPEN 状态下还需等待多少毫秒才进入半开；非 OPEN 返回 0 */
    public synchronized long retryAfterMs() {
        if (state != State.OPEN) {
            return 0;
        }
        return Math.max(0, openWaitMs - (clock.getAsLong() - openedAt));
    }

    /** 窗口内的统计快照，调试用 */
    public synchronized Snapshot snapshot() {
        roll(clock.getAsLong());
        int t = 0;
        int f = 0;
        for (int i = 0; i < BUCKETS; i++) {
            t += totals[i];
            f += failures[i];
        }
        return new Snapshot(state, t, f, consecutiveFailures, windowMs, retryAfterMs());
    }

    /**
     * 执行受保护的调用。
     *
     * @throws CircuitOpenException 熔断器处于 OPEN（或半开探测名额已用完）时抛出，
     *                              此时 supplier <b>完全不会被执行</b>
     */
    public <T> T call(Supplier<T> supplier) {
        acquire();
        try {
            T value = supplier.get();
            recordSuccess();
            return value;
        } catch (RuntimeException | Error e) {
            recordFailure();
            throw e;
        }
    }

    /** 判断本次是否放行，不放行直接抛。拿到名额后由 recordXxx 归还结果。 */
    private synchronized void acquire() {
        long now = clock.getAsLong();
        roll(now);
        switch (state) {
            case OPEN -> {
                long waited = now - openedAt;
                if (waited >= openWaitMs) {
                    halfOpenAttempts = 0;
                    halfOpenSuccess = 0;
                    halfOpenAt = now;
                    transition(State.HALF_OPEN, now);
                } else {
                    throw new CircuitOpenException(name, openWaitMs - waited);
                }
            }
            case HALF_OPEN -> {
                // 半开也要有超时：如果探测请求迟迟不来（用户都走光了），
                // 不能一直停在半开放行流量
                if (now - halfOpenAt >= openWaitMs) {
                    openedAt = now;
                    halfOpenAttempts = 0;
                    halfOpenSuccess = 0;
                    transition(State.OPEN, now);
                    throw new CircuitOpenException(name, openWaitMs);
                }
                if (halfOpenAttempts >= halfOpenPermits) {
                    throw new CircuitOpenException(name, openWaitMs);
                }
                halfOpenAttempts++;
            }
            case CLOSED -> {
                // 正常放行
            }
        }
    }

    private synchronized void recordSuccess() {
        long now = clock.getAsLong();
        roll(now);
        int idx = index(now);
        totals[idx]++;
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            halfOpenSuccess++;
            if (halfOpenSuccess >= halfOpenPermits) {
                // 探测全部成功 → 认定上游恢复，清空窗口重新开始统计
                clearWindow();
                halfOpenAttempts = 0;
                halfOpenSuccess = 0;
                transition(State.CLOSED, now);
            }
        }
    }

    private synchronized void recordFailure() {
        long now = clock.getAsLong();
        roll(now);
        int idx = index(now);
        totals[idx]++;
        failures[idx]++;
        consecutiveFailures++;

        if (state == State.HALF_OPEN) {
            // 探测失败 → 立刻回到 OPEN 并重新计时，别让刚恢复的上游再挨一轮
            openedAt = now;
            transition(State.OPEN, now);
            return;
        }
        if (state == State.CLOSED) {
            // 路一：连续失败。不依赖窗口，专治「单次耗时 ≥ 窗口长度、窗口永远被滑空」的慢失败
            if (consecutiveFailures >= consecutiveFailureLimit) {
                openedAt = now;
                transition(State.OPEN, now);
                return;
            }
            // 路二：窗口内失败率
            int t = 0;
            int f = 0;
            for (int i = 0; i < BUCKETS; i++) {
                t += totals[i];
                f += failures[i];
            }
            // f * 100 而不是浮点除法：避免 0.5 这类阈值在整数比较时的边界问题
            if (t >= minSamples && f * 100 >= failureRateThreshold * t) {
                openedAt = now;
                transition(State.OPEN, now);
            }
        }
    }

    private void transition(State next, long now) {
        if (next == state) {
            return;
        }
        State prev = state;
        state = next;
        onStateChange.accept(name + ": " + prev + " -> " + next + " @" + now);
    }

    private void clearWindow() {
        for (int i = 0; i < BUCKETS; i++) {
            totals[i] = 0;
            failures[i] = 0;
        }
    }

    /** 把已经滑出窗口的桶清零。只在状态变更/记录时调用，不做后台线程。 */
    private void roll(long now) {
        long bucket = now / bucketMs;
        if (bucket == currentBucket) {
            return;
        }
        long steps = Math.min(bucket - currentBucket, BUCKETS);
        for (int i = 0; i < steps; i++) {
            int idx = (int) Math.floorMod(bucket - i, (long) BUCKETS);
            totals[idx] = 0;
            failures[idx] = 0;
        }
        // 时间跳变过大（机器休眠、时钟回拨）时直接清干净，避免残留旧数据误判
        if (steps >= BUCKETS) {
            clearWindow();
        }
        currentBucket = bucket;
    }

    private int index(long now) {
        return (int) Math.floorMod(now / bucketMs, (long) BUCKETS);
    }

    /** 窗口统计快照 */
    public record Snapshot(State state, int total, int failures, int consecutiveFailures,
                           long windowMs, long retryAfterMs) {
        public int failureRatePercent() {
            return total == 0 ? 0 : failures * 100 / total;
        }
    }
}
