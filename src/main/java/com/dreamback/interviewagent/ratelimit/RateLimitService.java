package com.dreamback.interviewagent.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
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
    }

    /** 窗口大小：60 秒（毫秒）—— 与 qpm「每分钟 N 次」对应 */
    private static final long WINDOW_MS = 60_000L;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter errorCounter;

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider,
                            MeterRegistry registry) {
        this.redisProvider = redisProvider;
        this.allowedCounter = Counter.builder("app_ratelimit").tag("result", "allowed").register(registry);
        this.deniedCounter = Counter.builder("app_ratelimit").tag("result", "denied").register(registry);
        this.errorCounter = Counter.builder("app_ratelimit").tag("result", "error").register(registry);
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
}
