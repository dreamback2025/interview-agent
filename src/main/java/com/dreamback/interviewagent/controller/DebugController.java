package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.repository.InterviewAnalysisRepository;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import com.dreamback.interviewagent.repository.KnowledgeDocRepository;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.MemoryService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 自检用：把注入给模型的「历史记忆」原文打出来，方便确认会话记忆真的生效。 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.debug.enabled", havingValue = "true", matchIfMissing = true)
public class DebugController {

    private final MemoryService memoryService;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAnalysisRepository analysisRepository;
    private final KnowledgeDocRepository docRepository;
    private final UserContext userContext;
    private final Environment environment;

    @GetMapping("/memory")
    public String memory(@RequestParam(required = false) Long excludeRecordId,
                         @RequestParam(defaultValue = "3") int limit) {
        // 只展示当前用户自己的历史记忆，避免调试端点越权泄露别人的复盘结论
        String ctx = memoryService.recentInsights(userContext.currentUserId().orElse(null), excludeRecordId, limit);
        return ctx.isBlank() ? "（无历史记忆：其他记录还没有分析结果，或 app.memory.enabled=false）" : ctx;
    }

    /**
     * 当前实例用的是哪个库、有多少数据 —— 页面顶部会显示这个，
     * 避免「多实例 + 多数据库」时看错库（踩过这个坑）。
     */
    @GetMapping("/info")
    public Map<String, Object> info(@Value("${spring.datasource.url:}") String bizUrl,
                                    @Value("${app.rag.vectordb.url:}") String vectorUrl,
                                    @Value("${app.rag.embedding:ollama}") String embedding,
                                    @Value("${app.rag.hybrid.enabled:true}") boolean hybrid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profiles", String.join(",", environment.getActiveProfiles()));
        m.put("bizDb", mask(bizUrl));
        m.put("vectorDb", mask(vectorUrl));
        m.put("embedding", embedding);
        // 检索模式：hybrid（向量+关键词+RRF）/ vector（纯向量）—— 评测报告据此标注，避免两组数据混淆
        m.put("retrieval", hybrid ? "hybrid" : "vector");
        m.put("records", recordRepository.count());
        m.put("analyses", analysisRepository.count());
        m.put("knowledgeDocs", docRepository.count());
        return m;
    }

    /** 去掉连接串里的账号密码，避免泄露 */
    static String mask(String url) {
        if (url == null || url.isBlank()) {
            return "(未配置)";
        }
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        return base.replaceAll("(?i)(password=)[^;&]*", "$1***");
    }
}
