package com.dreamback.interviewagent.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口限流（基于 Redis ZSET + Lua 原子操作）。
 *
 * <p>为什么用滑动窗口而不是固定窗口：固定窗口在边界会突发（59 秒 N 次 + 第 1 秒 N 次 = 2N 次/秒），
 * 滑动窗口以「当前时刻往前推 60 秒」为窗口，更精确。
 *
 * <p>为什么用 Lua：ZREMRANGEBYSCORE → ZCARD → ZADD → PEXPIRE 四步必须原子，
 * 否则并发下多个请求会同时通过判断再各自 ZADD，限流失效。
 *
 * <p>降级：Redis 不可用时直接放行（与项目「可降级组件不阻断主流程」的哲学一致）——
 * 限流挂了最多是模型多烧几个 token，不能让用户连分析都用不了。
 */
@Slf4j
@Component
public class RateLimitService {

    private static final DefaultRedisScript<Long> ACQUIRE;
    private static final DefaultRedisScript<Long> ENTER;

    static {
        ACQUIRE = new DefaultRedisScript<>();
        ACQUIRE.setScriptText(
                "local key = KEYS[1] " +
                "local now = tonumber(ARGV[1]) " +
                "local window = tonumber(ARGV[2]) " +
                "local limit = tonumber(ARGV[3]) " +
                "local member = ARGV[4] " +
                // 先清掉窗口外的旧请求
                "redis.call('ZREMRANGEBYSCORE', key, 0, now - window) " +
                "local count = redis.call('ZCARD', key) " +
                // 已达上限：拒绝（拒绝的请求不占名额，避免被恶意占满）
                "if count >= limit then return 0 end " +
                "redis.call('ZADD', key, now, member) " +
                // 设过期避免 key 永久留存
                "redis.call('PEXPIRE', key, window) " +
                "return 1"
        );
        ACQUIRE.setResultType(Long.class);

        // 「在飞数」计数：进入时登记一个 member，退出时删掉。
        // 与滑动窗口的区别：窗口只关心「发生了多少次」，这里关心「现在还有多少没结束」。
        ENTER = new DefaultRedisScript<>();
        ENTER.setScriptText(
                "local key = KEYS[1] " +
                "local now = tonumber(ARGV[1]) " +
                "local limit = tonumber(ARGV[2]) " +
                "local member = ARGV[3] " +
                "local stale = tonumber(ARGV[4]) " +
                // 清掉「早就该结束却没被释放」的残留（进程被 kill、网络异常导致 finally 没跑到）
                "redis.call('ZREMRANGEBYSCORE', key, 0, now - stale) " +
                "local count = redis.call('ZCARD', key) " +
                "if count >= limit then return 0 end " +
                "redis.call('ZADD', key, now, member) " +
                "redis.call('PEXPIRE', key, stale) " +
                "return 1"
        );
        ENTER.setResultType(Long.class);
    }

    /** 窗口大小：60 秒（毫秒）—— 与 qpm「每分钟 N 次」对应 */
    private static final long WINDOW_MS = 60_000L;

    /**
     * 在飞项的「最长可能存活时间」：超过这个时间还没被释放的，视为进程崩溃留下的残骸，直接清掉。
     * 取值要大于单请求最长耗时（含 LLM 超时），否则会把正常请求误判成残骸。
     */
    private static final long INFLIGHT_STALE_MS = 600_000L;

    /** 本机兜底令牌的前缀：用来区分「该走 Redis 删」还是「该走本地减」 */
    private static final String LOCAL_PREFIX = "local:";

    /** Redis 不可用时的本地兜底计数（单机部署仍能保住并发约束） */
    private final ConcurrentMap<String, AtomicInteger> localInFlight = new ConcurrentHashMap<>();

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter errorCounter;
    private final Counter inFlightDeniedCounter;

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider,
                            MeterRegistry registry) {
        this.redisProvider = redisProvider;
        this.allowedCounter = Counter.builder("app_ratelimit").tag("result", "allowed").register(registry);
        this.deniedCounter = Counter.builder("app_ratelimit").tag("result", "denied").register(registry);
        this.errorCounter = Counter.builder("app_ratelimit").tag("result", "error").register(registry);
        this.inFlightDeniedCounter = Counter.builder("app_ratelimit").tag("result", "inflight_denied").register(registry);
    }

    /**
     * 尝试获取一个令牌。
     *
     * @param dimension 限流维度（由切面拼好，如 "user:5:analyze"）
     * @param qpm       每分钟允许的请求数
     * @return true=通过；false=超限；Redis 不可用时返回 true（降级放行）
     */
    public boolean tryAcquire(String dimension, int qpm) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // Redis 未启用：降级放行（不计数，因为没 bean 也没法记）
            return true;
        }
        try {
            String key = "ratelimit:" + dimension;
            Long result = redis.execute(
                    ACQUIRE,
                    List.of(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(WINDOW_MS),
                    String.valueOf(qpm),
                    UUID.randomUUID().toString()   // member 必须唯一，否则同窗口内的请求会互相覆盖
            );
            boolean allowed = result != null && result == 1L;
            if (allowed) {
                allowedCounter.increment();
            } else {
                deniedCounter.increment();
                log.warn("限流触发：dimension={} qpm={}（当前窗口已满）", dimension, qpm);
            }
            return allowed;
        } catch (Exception e) {
            // Redis 连接异常：降级放行，记 error 指标（不阻断主流程）
            errorCounter.increment();
            log.warn("限流检查失败，本次放行：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 进入「在飞」计数 —— 记录的是「现在还有几个没结束」，而不是「一分钟来了几个」。
     *
     * <p>为什么单独要这个维度：频率限制挡不住长耗时请求堆积。qpm=10、每次 25 秒，
     * 稳态就是 4 个并发；上游一慢到 50 秒，同样的 qpm 变成 8 个并发 —— 限流值没变，
     * 系统压力却翻倍。直接限在飞数，才不会因为耗时漂移而失灵。
     *
     * @param dimension 维度（如 "u5:analyze" / "global:analyze"）
     * @param limit     同时在飞上限
     * @return 令牌，需配合 {@link #leave} 在 finally 里释放；被限流时返回 null
     */
    public String enter(String dimension, int limit) {
        String token = UUID.randomUUID().toString();
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return enterLocal(dimension, limit, token);
        }
        try {
            Long ok = redis.execute(
                    ENTER,
                    List.of("inflight:" + dimension),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(limit),
                    token,
                    String.valueOf(INFLIGHT_STALE_MS));
            if (ok != null && ok == 1L) {
                allowedCounter.increment();
                return token;
            }
            inFlightDeniedCounter.increment();
            log.warn("并发限流触发：dimension={} limit={}（已有这么多请求在跑）", dimension, limit);
            return null;
        } catch (Exception e) {
            // Redis 挂了不能因此拒绝请求，退化成本机计数（单机部署仍然有效）
            errorCounter.increment();
            log.warn("并发限流检查失败，降级为本机计数：{}", e.getMessage());
            return enterLocal(dimension, limit, token);
        }
    }

    /** 释放一个在飞名额。必须在 finally 里调用，否则名额会一直占到 STALE 时间后被自动清理。 */
    public void leave(String dimension, String token) {
        if (token == null) {
            return;
        }
        if (token.startsWith(LOCAL_PREFIX)) {
            leaveLocal(dimension);
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForZSet().remove("inflight:" + dimension, token);
        } catch (Exception e) {
            // 释放失败不致命：残骸最多存活 INFLIGHT_STALE_MS，之后被 Lua 自动清掉
            log.warn("释放并发计数失败（残骸最多 {}s 后自动清理）：{}", INFLIGHT_STALE_MS / 1000, e.getMessage());
        }
    }

    private String enterLocal(String dimension, int limit, String token) {
        AtomicInteger counter = localInFlight.computeIfAbsent(dimension, k -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet();
            inFlightDeniedCounter.increment();
            log.warn("并发限流触发（本机计数）：dimension={} limit={}", dimension, limit);
            return null;
        }
        return LOCAL_PREFIX + token;
    }

    private void leaveLocal(String dimension) {
        localInFlight.computeIfPresent(dimension, (k, c) -> {
            c.decrementAndGet();
            return c;
        });
    }
}
