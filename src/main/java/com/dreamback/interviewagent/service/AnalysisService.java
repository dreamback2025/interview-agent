package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.dto.AnalysisHistoryItem;
import com.dreamback.interviewagent.dto.AnalysisRecordDto;
import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.entity.InterviewAnalysis;
import com.dreamback.interviewagent.entity.InterviewQuestion;
import com.dreamback.interviewagent.entity.InterviewRecord;
import com.dreamback.interviewagent.llm.LlmService;
import com.dreamback.interviewagent.repository.InterviewAnalysisRepository;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import com.dreamback.interviewagent.util.Digest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** 面试复盘：录入 -> LLM 分析弱项 -> 输出补强建议。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisService {

    private static final String SYSTEM_PROMPT = """
            你是一名资深面试复盘教练，擅长从一次真实面试中定位候选人的能力短板。
            输入包含：公司、岗位、目标 JD、每道题的「问题 / 我的回答 / 面试官反馈 / 打分 / 标签」。
            请输出结构化复盘报告，要求：
            1. 判断哪些问题答得好、哪些答得差，理由要具体到回答内容，不要空话；
            2. 从答得差的问题中归纳知识盲区，必须是可验证的技术点，不要写"表达能力"这类空泛词；
            3. 给出下次优先补强的 3 个点，每个点配一条可执行的练习建议（具体到做什么、产出什么）；
            4. 全部使用中文。
            """;

    /** 流式复盘的 System Prompt：要求 Markdown 结构化，方便前端直接渲染 */
    private static final String STREAM_SYSTEM_PROMPT = """
            你是一名资深面试复盘教练。请根据输入的面试记录输出一份 Markdown 格式的复盘报告，结构固定为：

            # 复盘报告
            ## 总评
            （2-3 句话，点明最致命的问题）
            ## 答得好的
            ## 答得差的
            ## 知识盲区
            ## 下次优先补强的 3 个点
            （每个点给出具体、可执行的练习建议）

            要求：理由要具体到回答内容，不要空话；盲区必须是可验证的技术点；
            如果参考信息里指出了「老问题反复出现」，要明确点名。全部使用中文，直接输出 Markdown，不要额外解释。
            """;

    /**
     * Prompt 版本：改动 System Prompt 或输出结构时递增，缓存 key 随之变化 ——
     * 旧缓存自然失效，不需要手工清理。
     */
    private static final String PROMPT_VERSION = "v1";

    private final InterviewRecordRepository recordRepository;
    private final InterviewAnalysisRepository analysisRepository;
    private final LlmService llmService;
    private final KnowledgeService knowledgeService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;
    /** 用 ObjectProvider：缓存关闭时不创建 bean，避免注入失败 */
    private final ObjectProvider<CacheService> cacheProvider;

    @Transactional
    public AnalysisReport analyze(Long recordId) {
        InterviewRecord record = recordRepository.findByIdWithQuestions(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试记录不存在: " + recordId));
        if (record.getQuestions() == null || record.getQuestions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该记录没有任何题目，无法分析");
        }

        // 幂等：内容相同的请求直接返回上次结果，不重复调用模型（省 token 也省时间）
        CacheService cache = cacheProvider.getIfAvailable();
        String cacheKey = analysisCacheKey(record);
        if (cache != null) {
            AnalysisReport cached = cache.get(cacheKey, AnalysisReport.class);
            if (cached != null) {
                log.info("分析结果命中缓存，跳过模型调用：recordId={}", recordId);
                return cached;
            }
        }

        AnalysisReport report = llmService.structured(
                SYSTEM_PROMPT, buildUserPrompt(record),
                AnalysisReport.class, () -> heuristic(record));

        persistWeakPoints(record, report);
        saveAnalysis(record, report);
        if (cache != null) {
            cache.put(cacheKey, report);
        }
        return report;
    }

    /**
     * 缓存 key = prompt版本 + 记录id + 内容指纹。
     * 内容指纹覆盖题目/回答/反馈/打分，任一变化都会自动换新 key，无需手动失效。
     */
    private String analysisCacheKey(InterviewRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl2(r.getPosition())).append('|').append(nvl2(r.getJd())).append('|');
        for (InterviewQuestion q : r.getQuestions()) {
            sb.append(nvl2(q.getQuestion())).append('|').append(nvl2(q.getMyAnswer())).append('|')
              .append(nvl2(q.getFeedback())).append('|').append(q.getScore()).append('|');
        }
        return "analysis:" + PROMPT_VERSION + ":" + r.getId() + ":" + Digest.sha256Short(sb.toString());
    }

    /** 供 Agent 模式复用：只落库，不重复分析 */
    public void saveAnalysis(Long recordId, AnalysisReport report) {
        InterviewRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试记录不存在: " + recordId));
        saveAnalysis(record, report);
    }

    /**
     * 检索知识库，把相关片段塞进分析 Prompt（RAG 增强）。
     * 知识库不可用时静默降级为空，绝不能因此让分析失败。
     */
    private String retrieveKnowledgeContext(InterviewRecord r) {
        try {
            Set<String> tags = new LinkedHashSet<>();
            for (InterviewQuestion q : r.getQuestions()) {
                if (q.getTags() != null) {
                    for (String t : q.getTags().split("[,，]")) {
                        if (!t.isBlank()) {
                            tags.add(t.trim());
                        }
                    }
                }
            }
            String query = (nvl2(r.getPosition()) + " " + String.join(" ", tags)).trim();
            if (query.isEmpty() && !r.getQuestions().isEmpty()) {
                query = nvl2(r.getQuestions().get(0).getQuestion());
            }
            if (query.isEmpty()) {
                return "";
            }
            var hits = knowledgeService.search(query, 3);
            if (hits.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(
                    "\n\n【知识库相关片段】下面是候选人自己笔记里的内容，请判断面试回答是否覆盖了这些点：\n");
            for (int i = 0; i < hits.size(); i++) {
                sb.append('[').append(i + 1).append("] ").append(hits.get(i).getContent()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("知识库检索不可用，本次分析不携带知识片段：{}", e.getMessage());
            return "";
        }
    }

    /** 报告全文落库，保留历史，便于回看。 */
    private void saveAnalysis(InterviewRecord record, AnalysisReport report) {
        try {
            InterviewAnalysis entity = new InterviewAnalysis();
            entity.setRecord(record);
            entity.setModel(llmService.mode());
            entity.setSummary(report.getSummary());
            entity.setReportJson(objectMapper.writeValueAsString(report));
            analysisRepository.save(entity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分析报告序列化失败: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<AnalysisHistoryItem> history(Long recordId) {
        return analysisRepository.findByRecordIdOrderByCreatedAtDesc(recordId).stream()
                .map(a -> {
                    AnalysisHistoryItem item = new AnalysisHistoryItem();
                    item.setId(a.getId());
                    item.setRecordId(a.getRecord() == null ? null : a.getRecord().getId());
                    item.setModel(a.getModel());
                    item.setSummary(a.getSummary());
                    item.setCreatedAt(a.getCreatedAt());
                    return item;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AnalysisRecordDto> all() {
        return analysisRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AnalysisRecordDto getAnalysis(Long analysisId) {
        InterviewAnalysis a = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "分析报告不存在: " + analysisId));
        return toDto(a);
    }

    private AnalysisRecordDto toDto(InterviewAnalysis a) {
        AnalysisRecordDto dto = new AnalysisRecordDto();
        dto.setId(a.getId());
        dto.setRecordId(a.getRecord() == null ? null : a.getRecord().getId());
        dto.setModel(a.getModel());
        dto.setCreatedAt(a.getCreatedAt());
        try {
            dto.setReport(objectMapper.readValue(a.getReportJson(), AnalysisReport.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("历史报告解析失败: " + e.getMessage(), e);
        }
        return dto;
    }

    public String buildUserPrompt(InterviewRecord r) {
        return doBuildUserPrompt(r) + retrieveKnowledgeContext(r) + memoryService.recentInsights(r.getId(), 3);
    }

    /**
     * 流式复盘：输出 Markdown（不是 JSON，因为流式无法做结构化校验），逐段推给前端。
     */
    public Flux<String> streamAnalysis(Long recordId) {
        InterviewRecord record = recordRepository.findByIdWithQuestions(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试记录不存在: " + recordId));
        if (record.getQuestions() == null || record.getQuestions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该记录没有任何题目，无法分析");
        }
        return llmService.stream(STREAM_SYSTEM_PROMPT, buildUserPrompt(record));
    }

    private String doBuildUserPrompt(InterviewRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("公司：").append(nvl(r.getCompany())).append('\n');
        sb.append("岗位：").append(nvl(r.getPosition())).append('\n');
        sb.append("面试日期：").append(r.getInterviewDate() == null ? "未填" : r.getInterviewDate()).append('\n');
        sb.append("结果：").append(nvl(r.getResult())).append('\n');
        sb.append("目标 JD：\n").append(nvl(r.getJd())).append("\n\n");
        sb.append("题目记录：\n");
        int i = 1;
        for (InterviewQuestion q : r.getQuestions()) {
            sb.append('[').append(i++).append("] 问题：").append(nvl(q.getQuestion())).append('\n');
            sb.append("    我的回答：").append(nvl(q.getMyAnswer())).append('\n');
            sb.append("    面试官反馈：").append(nvl(q.getFeedback())).append('\n');
            sb.append("    打分：").append(q.getScore() == null ? "未打分" : q.getScore()).append('\n');
            sb.append("    标签：").append(nvl(q.getTags())).append("\n\n");
        }
        if (r.getNotes() != null && !r.getNotes().isBlank()) {
            sb.append("补充备注：").append(r.getNotes()).append('\n');
        }
        return sb.toString();
    }

    /** 把弱项回写到 interview_question.weak_points，方便后续检索/统计。 */
    private void persistWeakPoints(InterviewRecord record, AnalysisReport report) {
        if (report.getWeakQuestions() == null || report.getWeakQuestions().isEmpty()) {
            return;
        }
        for (AnalysisReport.QuestionReview review : report.getWeakQuestions()) {
            if (review.getQuestion() == null) {
                continue;
            }
            for (InterviewQuestion q : record.getQuestions()) {
                if (q.getQuestion() != null && q.getQuestion().equalsIgnoreCase(review.getQuestion().trim())) {
                    q.setWeakPoints(review.getReason());
                    break;
                }
            }
        }
        recordRepository.save(record);
    }

    /** 离线兜底：无 Key 时按规则给出一份可验证的报告（同时保证链路可跑）。 */
    private AnalysisReport heuristic(InterviewRecord r) {
        AnalysisReport report = new AnalysisReport();
        List<InterviewQuestion> weak = new ArrayList<>();
        List<InterviewQuestion> good = new ArrayList<>();

        for (InterviewQuestion q : r.getQuestions()) {
            boolean bad = (q.getScore() != null && q.getScore() < 7)
                    || (q.getMyAnswer() == null || q.getMyAnswer().isBlank());
            if (bad) {
                weak.add(q);
            } else {
                good.add(q);
            }
        }

        for (InterviewQuestion q : good) {
            AnalysisReport.QuestionReview review = new AnalysisReport.QuestionReview();
            review.setQuestion(q.getQuestion());
            review.setReason("回答完整且有细节，保持当前表述方式即可。");
            report.getGoodQuestions().add(review);
        }
        Set<String> gaps = new LinkedHashSet<>();
        for (InterviewQuestion q : weak) {
            AnalysisReport.QuestionReview review = new AnalysisReport.QuestionReview();
            review.setQuestion(q.getQuestion());
            review.setReason("回答缺失关键细节" + (q.getScore() == null ? "（未打分）" : "，自评/打分 " + q.getScore()));
            report.getWeakQuestions().add(review);
            if (q.getTags() != null && !q.getTags().isBlank()) {
                for (String t : q.getTags().split("[,，]")) {
                    if (!t.isBlank()) {
                        gaps.add(t.trim());
                    }
                }
            }
        }
        report.getKnowledgeGaps().addAll(gaps);

        int n = 0;
        for (String gap : gaps) {
            if (n++ >= 3) {
                break;
            }
            AnalysisReport.FocusPoint p = new AnalysisReport.FocusPoint();
            p.setPoint(gap);
            p.setPracticeAdvice("围绕「" + gap + "」整理 10 个高频考点，逐条写成 200 字以内的口述稿并录音复述一遍。");
            report.getTopPriorities().add(p);
        }
        report.setSummary("[stub 模式] 共 " + r.getQuestions().size() + " 题，其中 " + weak.size()
                + " 题答得较差。配置 DEEPSEEK_API_KEY 后重新分析可获得真实复盘。");
        return report;
    }

    private static String nvl2(String s) {
        return s == null ? "" : s;
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "（未填写）" : s;
    }
}
