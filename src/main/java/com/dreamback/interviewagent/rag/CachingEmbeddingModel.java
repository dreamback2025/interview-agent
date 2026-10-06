package com.dreamback.interviewagent.rag;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.util.Digest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * embedding 缓存：装饰器，包在真实 EmbeddingModel（Ollama bge-m3 / 哈希兜底）外面。
 *
 * <p><b>为什么缓存它</b>：实测一次分析端到端 12.7s，模型调用只占 6.3s，另一半花在
 * DB + 记忆注入 + 每道错题一次的 embedding 上，且随错题数线性增长。而 embedding 是
 * <b>纯函数</b>—— 同样的模型 + 同样的文本永远得到同样的向量，这是可缓存的前提
 * （LLM 生成不是纯函数，所以不能这么缓存）。
 *
 * <h3>只拦 {@link #call(EmbeddingRequest)} 这一个方法</h3>
 * Spring AI 的 {@code embed(String) / embed(List<String>) / embed(List<Document>)}
 * 默认实现全部汇聚到 {@code call()}，所以只需重写这一个方法就能覆盖所有入口：
 * 入库的批量向量化、检索时的 query 向量化，都自动走缓存。
 *
 * <h3>两级缓存</h3>
 * <ol>
 *   <li><b>L1 进程内 LRU</b>（{@code LinkedHashMap} + 容量上限）：命中不产生任何序列化与网络开销。
 *       embedding 是纯函数，各实例各自预热不影响正确性，只影响命中率。</li>
 *   <li><b>L2 Redis</b>：跨实例共享、重启后仍有效。1024 维向量存 JSON 要 12KB+，
 *       这里按 float 二进制 + Base64 存（4KB → 5.5KB），比 JSON 小一半。
 *       Redis 不可用时静默跳过，与项目「可降级组件不阻断主流程」一致。</li>
 * </ol>
 *
 * <h3>缓存 key 必须带模型标识</h3>
 * key = {@code emb:<modelTag>:<sha256(text)>}。换模型（bge-m3 → 别的）后向量空间完全不同，
 * 若 key 里没有模型标识，会拿旧模型的向量去比新模型的向量，检索结果全错且<strong>不报错</strong>——
 * 这是缓存里最难查的一类故障，所以 modelTag 是构造必填项。
 */
@Slf4j
public class CachingEmbeddingModel implements EmbeddingModel {

    private static final String PREFIX = "emb:";

    private final EmbeddingModel delegate;
    private final String modelTag;
    private final int maxEntries;
    private final boolean redisEnabled;
    private final Supplier<CacheService> cacheProvider;
    private final Duration ttl;

    /** 访问序 LRU，所有访问在 {@code synchronized} 内 —— 命中路径是微秒级，不追求并发度 */
    private final Map<String, float[]> l1;

    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    private volatile Counter l1Counter;
    private volatile Counter l2Counter;
    private volatile Counter missCounter;
    private final Supplier<MeterRegistry> registryProvider;

    public CachingEmbeddingModel(EmbeddingModel delegate,
                                 String modelTag,
                                 int maxEntries,
                                 boolean redisEnabled,
                                 Supplier<CacheService> cacheProvider,
                                 Duration ttl,
                                 Supplier<MeterRegistry> registryProvider) {
        this.delegate = delegate;
        this.modelTag = modelTag;
        this.maxEntries = Math.max(1, maxEntries);
        this.redisEnabled = redisEnabled;
        this.cacheProvider = cacheProvider;
        this.ttl = ttl;
        this.registryProvider = registryProvider;
        this.l1 = new LinkedHashMap<>(64, 0.75f, true);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return delegate.call(request);
        }

        float[][] out = new float[texts.size()][];
        List<String> pending = new ArrayList<>();
        List<Integer> pendingIdx = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] hit = lookup(texts.get(i));
            if (hit != null) {
                out[i] = hit;
            } else {
                pending.add(texts.get(i));
                pendingIdx.add(i);
            }
        }

        if (!pending.isEmpty() && !compute(pending, request, out, pendingIdx)) {
            // 降级：逐条向量化失败（上游返回条数对不上等异常）时直接透传，宁可不缓存也不能返回错向量
            log.warn("批量 embedding 结果条数不符（期望{}），本次不走缓存", pending.size());
            return delegate.call(request);
        }

        List<Embedding> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(out[i], i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /** 只把未命中的文本发给上游，按原下标回填 */
    private boolean compute(List<String> pending, EmbeddingRequest request,
                            float[][] out, List<Integer> pendingIdx) {
        EmbeddingResponse resp = delegate.call(new EmbeddingRequest(pending, request.getOptions()));
        List<Embedding> results = resp.getResults();
        if (results == null || results.size() != pending.size()) {
            return false;
        }
        for (int k = 0; k < pending.size(); k++) {
            float[] v = results.get(k).getOutput();
            out[pendingIdx.get(k)] = v;
            store(pending.get(k), v);
        }
        return true;
    }

    @Override
    public float[] embed(Document document) {
        String text = document == null ? null : getEmbeddingContent(document);
        if (text == null) {
            // 空文本交给原实现处理（各模型对 null 的约定不同），不写进缓存
            return delegate.embed(document);
        }
        return embed(text);
    }

    /**
     * 不缓存 {@code dimensions()}。
     *
     * <p>接口的默认实现是 {@code embed("Test String").length} —— 那会把一条无意义的
     * "Test String" 塞进缓存，还会为了拿维度白跑一次模型。直接问委托对象。
     */
    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    // ── 缓存读写 ───────────────────────────────────────────────

    private float[] lookup(String text) {
        String key = keyOf(text);
        synchronized (l1) {
            float[] v = l1.get(key);
            if (v != null) {
                l1Hits.incrementAndGet();
                count(l1Counter, "l1");
                return v;
            }
        }
        float[] v = readL2(key);
        if (v != null) {
            l2Hits.incrementAndGet();
            count(l2Counter, "l2");
            putL1(key, v);
            return v;
        }
        misses.incrementAndGet();
        count(missCounter, "miss");
        return null;
    }

    private void store(String text, float[] v) {
        String key = keyOf(text);
        putL1(key, v);
        writeL2(key, v);
    }

    private void putL1(String key, float[] v) {
        synchronized (l1) {
            l1.put(key, v);
            if (l1.size() > maxEntries) {
                var it = l1.keySet().iterator();
                it.next();
                it.remove();
                evictions.incrementAndGet();
            }
        }
    }

    private float[] readL2(String key) {
        if (!redisEnabled) {
            return null;
        }
        try {
            CacheService cache = cacheProvider.get();
            if (cache == null) {
                return null;
            }
            String s = cache.get(key, String.class);
            return s == null || s.isBlank() ? null : decode(s);
        } catch (Exception e) {
            log.debug("embedding L2 读取失败，按未命中处理：{}", e.getMessage());
            return null;
        }
    }

    private void writeL2(String key, float[] v) {
        if (!redisEnabled) {
            return;
        }
        try {
            CacheService cache = cacheProvider.get();
            if (cache != null) {
                cache.put(key, encode(v), ttl);
            }
        } catch (Exception e) {
            log.debug("embedding L2 写入失败（不影响本次调用）：{}", e.getMessage());
        }
    }

    private String keyOf(String text) {
        return PREFIX + modelTag + ":" + Digest.sha256Short(text);
    }

    private void count(Counter c, String tag) {
        if (c == null) {
            // 首次调用时才注册指标：MeterRegistry 可能晚于本 bean 创建（见 BeanPostProcessor 场景）
            MeterRegistry registry = registryProvider == null ? null : registryProvider.get();
            if (registry != null) {
                Counter created = Counter.builder("app.embedding.cache").tag("result", tag).register(registry);
                if (tag.equals("l1")) {
                    l1Counter = created;
                } else if (tag.equals("l2")) {
                    l2Counter = created;
                } else {
                    missCounter = created;
                }
                created.increment();
            }
            return;
        }
        c.increment();
    }

    /** float 数组 ↔ Base64。1024 维：4KB 二进制 → 5.5KB 字符串，约为 JSON 数组的一半 */
    private static String encode(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4);
        for (float f : v) {
            bb.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private static float[] decode(String s) {
        byte[] b = Base64.getDecoder().decode(s);
        ByteBuffer bb = ByteBuffer.wrap(b);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = bb.getFloat();
        }
        return v;
    }

    public record Stats(long total, long l1Hits, long l2Hits, long misses,
                        long evictions, int l1Size, double hitRatePercent, String modelTag) {
    }

    public Stats stats() {
        long l1 = l1Hits.get();
        long l2 = l2Hits.get();
        long mi = misses.get();
        long total = l1 + l2 + mi;
        return new Stats(total, l1, l2, mi, evictions.get(), size(),
                total == 0 ? 0.0 : Math.round((l1 + l2) * 1000.0 / total) / 10.0, modelTag);
    }

    public int size() {
        synchronized (l1) {
            return l1.size();
        }
    }

    /** 仅供测试与运维：清空 L1（L2 由 TTL 自然过期） */
    public void clear() {
        synchronized (l1) {
            l1.clear();
        }
    }

}
