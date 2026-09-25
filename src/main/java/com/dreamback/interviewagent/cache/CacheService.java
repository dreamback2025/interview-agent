package com.dreamback.interviewagent.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 缓存服务：分析结果、检索结果。
 *
 * 设计原则 —— **缓存是可降级的增强**：
 * Redis 不可用时，所有读写操作静默失败，主流程照常执行，不会因为缓存挂掉导致分析失败。
 * 这与项目里 RAG 降级（检索失败仍可分析）是同一套思路。
 *
 * 指标通过 Micrometer 暴露（/actuator/prometheus）：app_cache_requests{result=hit|miss|error}
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    /** 连续失败时只告警一次，避免 Redis 长时间不可用时产生日志风暴 */
    private volatile boolean available = true;

    public CacheService(StringRedisTemplate redis,
                        ObjectMapper objectMapper,
                        MeterRegistry registry,
                        @Value("${app.cache.ttl:30m}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.hits = Counter.builder("app.cache.requests").tag("result", "hit").register(registry);
        this.misses = Counter.builder("app.cache.requests").tag("result", "miss").register(registry);
        this.errors = Counter.builder("app.cache.requests").tag("result", "error").register(registry);
        log.info("缓存已启用：ttl={}", ttl);
    }

    public <T> T get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                misses.increment();
                return null;
            }
            hits.increment();
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            errors.increment();
            markDown(e);
            return null;
        }
    }

    /** 带泛型的读取（如 List&lt;SearchResult&gt;），避免类型擦除导致反序列化失败 */
    public <T> T getTyped(String key, TypeReference<T> ref) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                misses.increment();
                return null;
            }
            hits.increment();
            return objectMapper.readValue(json, ref);
        } catch (Exception e) {
            errors.increment();
            markDown(e);
            return null;
        }
    }

    public void put(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            errors.increment();
            markDown(e);
        }
    }

    /** 防穿透：空结果也写入缓存，TTL 取 1/10，避免同一无效请求反复打到模型/数据库 */
    public void putEmpty(String key) {
        try {
            redis.opsForValue().set(key, "__EMPTY__", ttl.dividedBy(10).isZero() ? Duration.ofSeconds(30) : ttl.dividedBy(10));
        } catch (Exception e) {
            errors.increment();
            markDown(e);
        }
    }

    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            errors.increment();
            markDown(e);
        }
    }

    /**
     * 按前缀批量失效（知识库入库/删文档后调用）。
     * 键规模小，用 KEYS 足够；数据量上万后应改为 SCAN 避免阻塞。
     */
    public void evictByPrefix(String prefix) {
        try {
            var keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            errors.increment();
            markDown(e);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private void markDown(Exception e) {
        if (available) {
            log.warn("缓存不可用，已降级为直连（后续不再重复告警）：{}", e.toString());
            available = false;
        }
    }
}
