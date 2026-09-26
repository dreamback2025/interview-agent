package com.dreamback.interviewagent.agent;

import com.dreamback.interviewagent.config.JdProperties;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import com.dreamback.interviewagent.service.KnowledgeService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 关键词 = **配置兜底** ∪ **知识库标签（动态）**。
 *
 * <p>为什么要有动态来源：配置里的词是「通用 Java 后端技术栈」，
 * 如果用户面的是 Go / 前端 / 算法岗，那 34 个词几乎一个也命中不了。
 * 而用户自己导入的笔记标签恰恰就是他的技术域 —— 面 Java 的人得到 Java 系的词，
 * 面 Go 的人得到 Go 系的词，且**零维护**（补一篇新领域的笔记，词库自动扩展）。
 *
 * <p>这也和项目的定位一致：知识库是用户自己的资产，系统拿它做参照。
 *
 * <p>知识库不可用时（没起 pgvector / Ollama）静默降级为只用配置 —— 提炼功能不能因此失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeJdKeywordProvider implements JdKeywordProvider {

    private final JdProperties props;
    private final KnowledgeService knowledgeService;

    /** 标签不常变，缓存起来避免每次提炼都查库 */
    private volatile List<String> cache;
    private volatile long loadedAt;

    @Override
    public List<String> keywords() {
        List<String> cached = cache;
        if (cached != null && System.currentTimeMillis() - loadedAt < props.getKeywordsCacheMs()) {
            return cached;
        }
        Set<String> all = new LinkedHashSet<>();

        // 1) 配置里的：兜底 + 可手工补充
        for (String k : props.getKeywords()) {
            if (k != null && !k.isBlank()) {
                all.add(k.trim());
            }
        }

        // 2) 动态的：用户笔记的标签 = 他自己的技术栈（KnowledgeService 已按当前用户过滤）
        int tagCount = 0;
        if (props.isDynamicTags()) {
            try {
                for (KnowledgeDoc d : knowledgeService.docs()) {
                    if (d.getTags() == null || d.getTags().isBlank()) {
                        continue;
                    }
                    for (String t : d.getTags().split("[,，]")) {
                        if (!t.isBlank()) {
                            all.add(t.trim());
                            tagCount++;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("知识库标签不可用，本次只用配置关键词：{}", e.getMessage());
            }
        }

        List<String> fresh = new ArrayList<>(all);
        cache = fresh;
        loadedAt = System.currentTimeMillis();
        log.debug("关键词库刷新：共 {} 条（配置 {} + 标签 {}）", fresh.size(), props.getKeywords().size(), tagCount);
        return fresh;
    }

    /** 供调试/自检：当前关键词库的构成 */
    public String describe() {
        return "共 %d 条（配置 %d，动态标签 %s，缓存 %dms）".formatted(
                keywords().size(), props.getKeywords().size(),
                props.isDynamicTags() ? "开" : "关", props.getKeywordsCacheMs());
    }

    /** 供缓存失效：导入/删除笔记后调一下，下次提炼就用新标签 */
    public void invalidate() {
        cache = null;
    }
}
