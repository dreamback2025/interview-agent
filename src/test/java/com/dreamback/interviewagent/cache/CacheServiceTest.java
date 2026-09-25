package com.dreamback.interviewagent.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CacheServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final CacheService cache = new CacheService(redis, new ObjectMapper(),
            new SimpleMeterRegistry(), Duration.ofMinutes(30));

    @Test
    @DisplayName("命中缓存时反序列化为原对象")
    void hitReturnsValue() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("k")).thenReturn("{\"name\":\"Tom\"}");

        assertThat(cache.get("k", Person.class).name()).isEqualTo("Tom");
    }

    @Test
    @DisplayName("未命中返回 null，且不抛异常")
    void missReturnsNull() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("k")).thenReturn(null);

        assertThat(cache.get("k", Person.class)).isNull();
    }

    @Test
    @DisplayName("Redis 抛异常时静默降级：返回 null 且标记不可用，不向上抛")
    void degradesSilentlyOnError() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("connection refused"));

        assertThat(cache.get("k", Person.class)).isNull();
        cache.put("k", new Person("Tom"));
        assertThat(cache.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("泛型读取：List<T> 也能正确反序列化")
    void typedReadSupportsGeneric() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("k")).thenReturn("[{\"name\":\"A\"},{\"name\":\"B\"}]");

        List<Person> list = cache.getTyped("k",
                new com.fasterxml.jackson.core.type.TypeReference<List<Person>>() { });

        assertThat(list).hasSize(2);
        assertThat(list.get(1).name()).isEqualTo("B");
    }

    @Test
    @DisplayName("写入异常不影响调用方")
    void putNeverThrows() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("down"));

        cache.put("k", new Person("Tom"));
        cache.evict("k");
        cache.evictByPrefix("kbsearch:");
        assertThat(cache.isAvailable()).isFalse();
    }

    record Person(String name) { }
}
