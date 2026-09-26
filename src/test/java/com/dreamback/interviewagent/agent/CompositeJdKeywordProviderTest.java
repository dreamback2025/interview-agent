package com.dreamback.interviewagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dreamback.interviewagent.config.JdProperties;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import com.dreamback.interviewagent.service.KnowledgeService;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeJdKeywordProviderTest {

    private static KnowledgeDoc doc(String title, String tags) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setDocId("d-" + title);
        d.setTitle(title);
        d.setTags(tags);
        return d;
    }

    private static JdProperties props(List<String> base, boolean dynamic, long cacheMs) {
        JdProperties p = new JdProperties();
        p.setKeywords(base);
        p.setDynamicTags(dynamic);
        p.setKeywordsCacheMs(cacheMs);
        p.setMaxRequirements(12);
        return p;
    }

    @Test
    void 配置与知识库标签取并集并去重() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenReturn(List.of(
                doc("Redis 笔记", "redis-cache,Redis"),
                doc("JVM 笔记", "jvm-gc,JVM")));
        var provider = new CompositeJdKeywordProvider(props(List.of("Java", "Redis"), true, 60_000), ks);

        // 配置里的 "Redis" 与标签里的 "Redis" 只保留一次（去重），标签新词追加在后面
        assertThat(provider.keywords())
                .containsExactly("Java", "Redis", "redis-cache", "jvm-gc", "JVM");
    }

    @Test
    void 知识库不可用时静默降级为只用配置() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenThrow(new RuntimeException("RAG 未启用"));
        var provider = new CompositeJdKeywordProvider(props(List.of("Java", "JVM"), true, 60_000), ks);

        assertThat(provider.keywords()).containsExactly("Java", "JVM");
    }

    @Test
    void 关闭动态来源就不查知识库() {
        KnowledgeService ks = mock(KnowledgeService.class);
        var provider = new CompositeJdKeywordProvider(props(List.of("Java"), false, 60_000), ks);

        assertThat(provider.keywords()).containsExactly("Java");
        verify(ks, never()).docs();
    }

    @Test
    void 缓存期内不重复查库() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenReturn(List.of(doc("A", "redis-cache")));
        var provider = new CompositeJdKeywordProvider(props(List.of(), true, 60_000), ks);

        provider.keywords();
        provider.keywords();
        provider.keywords();
        verify(ks, times(1)).docs();
    }

    @Test
    void 缓存过期后重新拉取() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenReturn(List.of(doc("A", "redis-cache")));
        var provider = new CompositeJdKeywordProvider(props(List.of(), true, 0), ks);

        provider.keywords();
        provider.keywords();
        verify(ks, times(2)).docs();
    }

    @Test
    void 两者都为空时返回空列表_由工具方法负责退化处理() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenReturn(List.of());
        var provider = new CompositeJdKeywordProvider(props(List.of(), true, 60_000), ks);

        assertThat(provider.keywords()).isEmpty();
    }

    @Test
    void 标签支持中英文逗号分隔() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenReturn(List.of(doc("A", "jvm-gc，JVM,并发")));
        var provider = new CompositeJdKeywordProvider(props(List.of(), true, 60_000), ks);

        assertThat(provider.keywords()).containsExactly("jvm-gc", "JVM", "并发");
    }

    @Test
    void 无标签的文档不影响结果() {
        KnowledgeService ks = mock(KnowledgeService.class);
        when(ks.docs()).thenReturn(List.of(doc("A", null)));
        var provider = new CompositeJdKeywordProvider(props(List.of("Java"), true, 60_000), ks);

        assertThat(provider.keywords()).containsExactly("Java");
    }
}
