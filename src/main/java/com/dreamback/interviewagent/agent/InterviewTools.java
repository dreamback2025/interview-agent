package com.dreamback.interviewagent.agent;

import com.dreamback.interviewagent.config.JdProperties;
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

    private final KnowledgeService knowledgeService;
    private final InterviewQuestionRepository questionRepository;
    private final UserContext userContext;
    /** 关键词来源：配置 ∪ 知识库标签，见 {@link CompositeJdKeywordProvider} */
    private final JdKeywordProvider keywordProvider;
    private final JdProperties props;

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
        // 只转一次小写 —— 原来在每个关键词的循环里都 toLowerCase 整个 JD，几十个词就是几十次全量拷贝
        String lowerJd = jd.toLowerCase();

        Set<String> hit = new LinkedHashSet<>();
        for (String k : keywordProvider.keywords()) {
            if (matches(lowerJd, k)) {
                hit.add(k);
                if (hit.size() >= props.getMaxRequirements()) {
                    break;
                }
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

    /**
     * 关键词匹配：ASCII 技术词要求**词边界**，中文词直接包含。
     *
     * <p>为什么要区分：`contains` 会让 "Java" 命中 "JavaScript"、"GC" 命中 "GCC"、
     * "SQL" 命中 "MySQL" 里的子串 —— 提炼出来的是一堆噪音，还会误导后续分析。
     * 中文没有词边界可言，保持包含匹配即可。
     */
    static boolean matches(String lowerJd, String keyword) {
        if (keyword == null) {
            return false;
        }
        String k = keyword.trim().toLowerCase();
        if (k.isEmpty()) {
            return false;
        }
        boolean ascii = k.chars().allMatch(c -> c < 128);
        int from = 0;
        while (true) {
            int i = lowerJd.indexOf(k, from);
            if (i < 0) {
                return false;
            }
            if (!ascii) {
                return true;
            }
            int end = i + k.length();
            boolean leftOk = i == 0 || !isWordChar(lowerJd.charAt(i - 1));
            boolean rightOk = end >= lowerJd.length() || !isWordChar(lowerJd.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;   // 这一处只是子串，继续往后找真正的独立词
        }
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }
}
