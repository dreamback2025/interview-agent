package com.dreamback.interviewagent.agent;

import com.dreamback.interviewagent.entity.InterviewQuestion;
import com.dreamback.interviewagent.repository.InterviewQuestionRepository;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.KnowledgeService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** 暴露给模型的工具，模型自己决定调不调、调几次。 */
@Component
@RequiredArgsConstructor
public class InterviewTools {

    private static final List<String> TECH_KEYWORDS = List.of(
            "Java", "JVM", "GC", "并发", "多线程", "线程池", "锁", "MySQL", "索引", "SQL", "事务",
            "Redis", "缓存", "Spring", "Spring Boot", "MyBatis", "Kafka", "MQ", "消息队列",
            "分布式", "微服务", "Docker", "Kubernetes", "Linux", "网络", "HTTP", "TCP",
            "算法", "数据结构", "设计模式", "操作系统", "高并发", "性能调优", "Elasticsearch"
    );

    private final KnowledgeService knowledgeService;
    private final InterviewQuestionRepository questionRepository;
    private final UserContext userContext;

    @Tool(description = "检索知识库中与某个技术方向相关的笔记片段，返回最相关的原文，用于判断候选人是否掌握了该知识点")
    public List<String> searchKnowledgeBase(
            @ToolParam(description = "检索关键词，例如：G1 回收流程、MySQL 索引失效") String query) {
        try {
            var hits = knowledgeService.search(query, 3);
            List<String> out = new ArrayList<>();
            for (var h : hits) {
                out.add("[" + (h.getTitle() == null ? "笔记" : h.getTitle()) + "] " + h.getContent());
            }
            return out.isEmpty() ? List.of("（知识库中没有命中相关片段）") : out;
        } catch (Exception e) {
            return List.of("（知识库暂不可用：" + e.getMessage() + "）");
        }
    }

    @Tool(description = "检索历史面试中某个技术方向暴露过的弱项，返回当时的题目和弱项说明，用于判断是否是老问题反复出现")
    public List<String> searchWeakPoints(
            @ToolParam(description = "技术方向，例如：JVM、Redis、MySQL 索引") String topic) {
        if (topic == null || topic.isBlank()) {
            return List.of("（请提供要查询的技术方向）");
        }
        Long uid = userContext.currentUserId().orElse(null);
        List<InterviewQuestion> qs = uid == null
                ? questionRepository.searchWeakPoints(topic.trim(), PageRequest.of(0, 5))
                : questionRepository.searchWeakPointsByUser(topic.trim(), uid, PageRequest.of(0, 5));
        if (qs.isEmpty()) {
            return List.of("（历史上没有关于「" + topic + "」的弱项记录）");
        }
        List<String> out = new ArrayList<>();
        for (InterviewQuestion q : qs) {
            out.add("题目：" + q.getQuestion() + " ｜ 弱项：" + q.getWeakPoints());
        }
        return out;
    }

    @Tool(description = "从目标岗位的 JD 原文中提炼关键技术要求，用于对照候选人是否覆盖")
    public List<String> getJdRequirements(
            @ToolParam(description = "岗位 JD 原文") String jd) {
        if (jd == null || jd.isBlank()) {
            return List.of("（JD 为空）");
        }
        Set<String> hit = new LinkedHashSet<>();
        for (String k : TECH_KEYWORDS) {
            if (jd.toLowerCase().contains(k.toLowerCase())) {
                hit.add(k);
            }
        }
        if (!hit.isEmpty()) {
            return new ArrayList<>(hit);
        }
        // 关键词库没命中时，退化为按标点切分职责短语
        List<String> phrases = new ArrayList<>();
        for (String seg : jd.split("[。；;\n]")) {
            String s = seg.trim();
            if (s.length() >= 6) {
                phrases.add(s);
            }
            if (phrases.size() >= 5) {
                break;
            }
        }
        return phrases.isEmpty() ? List.of(jd.trim()) : phrases;
    }
}
