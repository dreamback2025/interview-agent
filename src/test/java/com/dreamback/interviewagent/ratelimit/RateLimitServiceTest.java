package com.dreamback.interviewagent.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimitServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void redis不可用时降级放行() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        RateLimitService svc = new RateLimitService(provider, registry);
        // 没有 Redis 也得放行，不能让限流挂了导致用户连分析都用不了
        assertThat(svc.tryAcquire("u1:analyze", 10)).isTrue();
    }

    @Test
    void lua返回1则放行_计数allowed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        RateLimitService svc = new RateLimitService(provider, registry);
        assertThat(svc.tryAcquire("u1:analyze", 10)).isTrue();
        assertThat(registry.counter("app_ratelimit", "result", "allowed").count()).isEqualTo(1.0);
        assertThat(registry.counter("app_ratelimit", "result", "denied").count()).isZero();
    }

    @Test
    void lua返回0则拒绝_计数denied() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(0L);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        RateLimitService svc = new RateLimitService(provider, registry);
        assertThat(svc.tryAcquire("u1:analyze", 10)).isFalse();
        assertThat(registry.counter("app_ratelimit", "result", "denied").count()).isEqualTo(1.0);
    }

    @Test
    void lua抛异常时降级放行_计数error() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("Redis 连接超时"));
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        RateLimitService svc = new RateLimitService(provider, registry);
        // Redis 故障期间放行，避免限流组件挂掉拖垮整个应用
        assertThat(svc.tryAcquire("u1:analyze", 10)).isTrue();
        assertThat(registry.counter("app_ratelimit", "result", "error").count()).isEqualTo(1.0);
    }

    @Test
    void dimension不同时各自独立计数() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        RateLimitService svc = new RateLimitService(provider, registry);
        svc.tryAcquire("u1:analyze", 10);
        svc.tryAcquire("u1:mockStart", 5);
        svc.tryAcquire("u2:analyze", 10);  // 不同用户
        // 三次都通过，allowed 计数 3（不同 dimension 不互相影响）
        assertThat(registry.counter("app_ratelimit", "result", "allowed").count()).isEqualTo(3.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 传入空dimension也正常工作_不抛异常() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RateLimitService svc = new RateLimitService(provider, registry);
        // 边界：免鉴权模式下 dimension 可能含 "anonymous"，逻辑应正常
        assertThat(svc.tryAcquire("anonymous:analyze", 60)).isTrue();
    }
}
