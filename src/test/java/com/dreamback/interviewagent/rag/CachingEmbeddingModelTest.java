package com.dreamback.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dreamback.interviewagent.cache.CacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class CachingEmbeddingModelTest {

    private static final String TAG = "ollama:bge-m3:4";

    /** 记录每次真正发给上游的批量请求，向量用「文本长度」代替，便于断言 */
    private static class Fake implements EmbeddingModel {
        final List<List<String>> calls = new ArrayList<>();
        int dims = 4;

        @Override
        public EmbeddingResponse call(EmbeddingRequest req) {
            calls.add(List.copyOf(req.getInstructions()));
            List<Embedding> out = new ArrayList<>();
            int i = 0;
            for (String t : req.getInstructions()) {
                float[] v = new float[dims];
                v[0] = t.length();
                out.add(new Embedding(v, i++));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return dims;
        }
    }

    private static CachingEmbeddingModel wrap(Fake delegate, CacheService cache) {
        return new CachingEmbeddingModel(delegate, TAG, 100, cache != null,
                () -> cache, Duration.ofDays(7), SimpleMeterRegistry::new);
    }

    private static CachingEmbeddingModel wrap(Fake delegate, CacheService cache, int max, String tag) {
        return new CachingEmbeddingModel(delegate, tag, max, cache != null,
                () -> cache, Duration.ofDays(7), SimpleMeterRegistry::new);
    }

    @Test
    void 同一文本第二次走L1_不再调用上游() {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null);

        assertThat(m.embed("线程池参数怎么设")[0]).isEqualTo(8f);
        assertThat(m.embed("线程池参数怎么设")[0]).isEqualTo(8f);

        assertThat(d.calls).hasSize(1);          // 只真正算了一次
        assertThat(m.stats().l1Hits()).isEqualTo(1);
        assertThat(m.stats().misses()).isEqualTo(1);
        assertThat(m.stats().hitRatePercent()).isEqualTo(50.0);
    }

    @Test
    void 批量请求只把未命中的发给上游_且顺序与输入一致() {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null);
        m.embed(List.of("A"));                    // 预热：A 进缓存

        List<float[]> out = m.embed(List.of("A", "BB", "CCC", "A"));

        // 第二次只应把 BB / CCC 发给上游
        assertThat(d.calls).hasSize(2);
        assertThat(d.calls.get(1)).containsExactly("BB", "CCC");
        // 顺序必须按输入还原，不能按上游返回顺序拼
        assertThat(out.get(0)[0]).isEqualTo(1f);
        assertThat(out.get(1)[0]).isEqualTo(2f);
        assertThat(out.get(2)[0]).isEqualTo(3f);
        assertThat(out.get(3)[0]).isEqualTo(1f);
    }

    @Test
    void 换模型标识必须换key_否则旧向量污染新向量空间() {
        Fake d = new Fake();
        CachingEmbeddingModel m1 = wrap(d, null, 100, "ollama:bge-m3:4");
        m1.embed("同一段文本");

        CachingEmbeddingModel m2 = new CachingEmbeddingModel(d, "ollama:mxbai-embed-large:4", 100,
                false, () -> null, Duration.ofDays(7), SimpleMeterRegistry::new);
        m2.embed("同一段文本");

        assertThat(d.calls).hasSize(2);   // 换模型 = 换缓存命名空间，必须重新算
        assertThat(m2.stats().misses()).isEqualTo(1);
    }

    @Test
    void L1超上限按LRU淘汰() {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null, 2, TAG);

        m.embed("t1");
        m.embed("t2");
        m.embed("t1");        // t1 再访问一次，变成最近使用
        m.embed("t3");        // 淘汰最久未用的 t2

        assertThat(m.size()).isEqualTo(2);
        assertThat(m.stats().evictions()).isEqualTo(1);
        m.embed("t2");        // t2 已被淘汰，需要重新计算
        assertThat(m.stats().misses()).isEqualTo(4);
    }

    @Test
    void L1未命中时读L2_并把结果回填L1() {
        Fake d = new Fake();
        CacheService cache = mock(CacheService.class);
        CachingEmbeddingModel m = wrap(d, cache);

        m.embed("缓存雪崩");                       // miss → 写 L2 + L1
        String encoded = capturePut(cache);
        assertThat(encoded).isNotBlank();

        m.clear();                                 // 清掉 L1，模拟进程刚启动/被淘汰
        when(cache.get(anyString(), eq(String.class))).thenReturn(encoded);

        assertThat(m.embed("缓存雪崩")[0]).isEqualTo(4f);
        assertThat(d.calls).hasSize(1);            // 没有再算
        assertThat(m.stats().l2Hits()).isEqualTo(1);
    }

    @Test
    void Redis异常时静默降级_不影响主流程() {
        Fake d = new Fake();
        CacheService cache = mock(CacheService.class);
        when(cache.get(anyString(), eq(String.class))).thenThrow(new RuntimeException("redis down"));
        doThrow(new RuntimeException("redis down")).when(cache).put(anyString(), any(), any(Duration.class));

        CachingEmbeddingModel m = wrap(d, cache);
        assertThat(m.embed("消息积压")[0]).isEqualTo(4f);
        assertThat(m.embed("消息积压")[0]).isEqualTo(4f);   // L1 仍然生效
        assertThat(d.calls).hasSize(1);
    }

    @Test
    void dimensions不触发embedding_避免为拿维度白跑一次模型() {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null);
        assertThat(m.dimensions()).isEqualTo(4);
        assertThat(d.calls).isEmpty();
    }

    @Test
    void embedDocument也走缓存() {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null);
        m.embed(new Document("索引下推"));
        m.embed(new Document("索引下推"));
        assertThat(d.calls).hasSize(1);
    }

    @Test
    void 空请求直接透传() {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null);
        m.call(new EmbeddingRequest(List.of(), null));
        assertThat(d.calls).hasSize(1);
    }

    @Test
    void 上游返回条数对不上时透传_不写脏缓存() {
        Fake bad = new Fake() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest req) {
                calls.add(List.copyOf(req.getInstructions()));
                return new EmbeddingResponse(List.of());   // 条数不符
            }
        };
        CachingEmbeddingModel m = wrap(bad, null);
        EmbeddingResponse resp = m.call(new EmbeddingRequest(List.of("x", "y"), null));
        assertThat(resp.getResults()).isEmpty();           // 上游给了什么就返回什么，不擅自裁剪
        assertThat(m.stats().total()).isEqualTo(2);        // 两次都算未命中，没写进缓存
        assertThat(m.size()).isZero();
    }

    /**
     * 缓存预热后并发读：命中路径必须是线程安全的（L1 用 synchronized + 访问序 LRU，
     * 访问本身会改链表结构，不加锁会 ConcurrentModificationException）。
     */
    @Test
    void 并发读已预热缓存_上游不再被调用() throws Exception {
        Fake d = new Fake();
        CachingEmbeddingModel m = wrap(d, null);
        m.embed("并发key");                       // 预热

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    m.embed("并发key");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(d.calls).hasSize(1);            // 一次都没重算
        assertThat(m.stats().l1Hits()).isEqualTo(n);
    }

    /** 从 mock 里取出写入 Redis 的编码值 */
    private static String capturePut(CacheService cache) {
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(cache).put(anyString(), captor.capture(), any(Duration.class));
        return String.valueOf(captor.getValue());
    }
}
