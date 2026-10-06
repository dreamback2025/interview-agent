package com.dreamback.interviewagent.service.impl;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.dto.AnalysisHistoryItem;
import com.dreamback.interviewagent.dto.AnalysisRecordDto;
import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.dto.RetrievalResult;
import com.dreamback.interviewagent.entity.InterviewAnalysis;
import com.dreamback.interviewagent.entity.InterviewQuestion;
import com.dreamback.interviewagent.entity.InterviewRecord;
import com.dreamback.interviewagent.llm.LlmService;
import com.dreamback.interviewagent.repository.InterviewAnalysisRepository;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.AnalysisService;
import com.dreamback.interviewagent.service.KnowledgeService;
import com.dreamback.interviewagent.service.MemoryService;
import com.dreamback.interviewagent.util.Digest;
import com.dreamback.interviewagent.observability.StageRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** 面试复盘分析实现。详见 {@link AnalysisService}。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisServiceImpl implements AnalysisService {

    private static final String SYSTEM_PROMPT = """
            你是一名资深面试复盘教练，擅长从一次真实面试中定位候选人的能力短板。
            输入包含：公司、岗位、目标 JD、每道题的「问题 / 我的回答 / 面试官反馈 / 打分 / 标签」。
            请输出结构化复盘报告，要求：
            1. 判断哪些问题答得好、哪些答得差，理由要具体到回答内容，不要空话；
            2. 从答得差的问题中归纳知识盲区，必须是可验证的技术点，不要写"表达能力"这类空泛词；
            3. 给出下次优先补强的 3 个点，每个点配一条可执行的练习建议（具体到做什么、产出什么）；
            4. 全部使用中文。
            """;

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

    private static final String PROMPT_VERSION = "v1";

    /** 单道错题的覆盖度判定超时（毫秒）—— 不该让一道题的检索拖垮整份报告 */
    private static final long COVERAGE_TIMEOUT_MS = 15_000L;

    private final InterviewRecordRepository recordRepository;
    private final InterviewAnalysisRepository analysisRepository;
    private final LlmService llmService;
    private final KnowledgeService knowledgeService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<CacheService> cacheProvider;
    private final UserContext userContext;
    private final TransactionTemplate roTransactionTemplate;
    private final TransactionTemplate rwTransactionTemplate;
    private final MeterRegistry registry;

    /**
     * 分析主流程。刻意拆成三段，事务边界只包含「读」和「写」两步毫秒级动作：
     *
     * <pre>
     *   [事务1 只读] 加载记录      ← 毫秒级，加载完立刻归还连接
     *   [无事务]     检索 + 模型调用 ← 20~40 秒，绝不占连接
     *   [事务2 读写] 落库           ← 毫秒级
     * </pre>
     *
     * <p>改之前 LLM 调用被包在事务里：连接被空转占用几十秒，默认 10 个连接的池子
     * 十来个并发就见底，后面所有请求卡在 {@code getConnection()} 上直到 30 秒超时。
     */
    @Override
    public AnalysisReport analyze(Long recordId) {
        // 分段计时是常驻的，不是排查时才加：端到端耗时只能报警不能定位，
        // 而「哪一段慢」在真实流量下会漂移（上游慢、知识库冷、错题数变多），
        // 等出问题再临时加代码，那时看到的已经不是出问题的那批请求了。
        StageRecorder t = StageRecorder.start("analyze", registry);
        try {
            InterviewRecord record = loadForAnalysis(recordId);
            t.mark("load");

            CacheService cache = cacheProvider.getIfAvailable();
            String cacheKey = analysisCacheKey(record);
            AnalysisReport cached = cache == null ? null : cache.get(cacheKey, AnalysisReport.class);
            t.mark("cache.get");
            if (cached != null) {
                log.info("分析结果命中缓存，跳过模型调用：recordId={}", recordId);
                // 覆盖度必须重判 —— 缓存 key 不含笔记状态，用户可能刚补了笔记
                annotateNoteCoverage(record, cached);
                t.mark("coverage");
                return cached;
            }

            // buildUserPrompt 内部会记 prompt.base / prompt.rag / prompt.memory 三段，
            // 它们发生在模型调用之前，顺序天然正确。
            AnalysisReport report = llmService.structured(
                    SYSTEM_PROMPT, buildUserPrompt(record, t),
                    AnalysisReport.class, () -> heuristic(record));
            t.mark("llm");

            persistReport(recordId, report);
            t.mark("persist");
            annotateNoteCoverage(record, report);
            t.mark("coverage");
            if (cache != null) {
                cache.put(cacheKey, report);
            }
            t.mark("cache.put");
            return report;
        } finally {
            t.finish();
        }
    }

    /**
     * 事务只包「加载」。
     *
     * <p>{@code getQuestions().size()} 不是废话：uid 为空的分支走的是 {@code findById}，
     * 没有 {@code @EntityGraph}，集合是懒加载的 —— 不在这里触碰一次，
     * 脱离事务后 buildUserPrompt 遍历 questions 就会抛 LazyInitializationException。
     */
    private InterviewRecord loadForAnalysis(Long recordId) {
        return roTransactionTemplate.execute(status -> {
            InterviewRecord record = loadRecordWithQuestions(recordId);
            if (record.getQuestions() == null || record.getQuestions().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该记录没有任何题目，无法分析");
            }
            record.getQuestions().size();
            return record;
        });
    }

    /**
     * 事务只包「写入」。重新查询实体而不是复用上面的 detached 对象：
     * detached 实体上的修改不会被 flush，直接 save 还可能把旧状态覆盖回去。
     */
    private void persistReport(Long recordId, AnalysisReport report) {
        rwTransactionTemplate.executeWithoutResult(status -> {
            InterviewRecord record = loadRecordWithQuestions(recordId);
            persistWeakPoints(record, report);
            saveAnalysis(record, report);
        });
    }

    /**
     * 给每道错题标注「用户自己的笔记里有没有覆盖这个知识点」。
     *
     * <p><b>这是本项目的核心差异化</b>：把「答错了」拆成两种完全不同的补强动作 ——
     * <ul>
     *   <li>笔记里有 → 不是知识缺口，该做的是**重新消化自己的笔记**</li>
     *   <li>笔记里没有 → 真正的**知识缺口**，该做的是**补一篇笔记**</li>
     * </ul>
     * 没有这个标注，所有错题都只能给一句笼统的「去复习一下」。
     *
     * <p>判定复用检索那套信号（向量相似度 + 关键词覆盖率，见 {@code RagRelevanceGate}），
     * 知识库不可用时静默跳过（noteSupported 留 null），绝不因此让分析失败。
     */
    private void annotateNoteCoverage(InterviewRecord record, AnalysisReport report) {
        List<AnalysisReport.QuestionReview> reviews = report.getWeakQuestions();
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        // 每道错题一次检索：串行的话总耗时随错题数线性增长（8 道错题 = 8 次 embedding 依次排队）。
        // 这些检索彼此独立、且知识库查询不依赖当前用户上下文，可以安全并行 ——
        // 用虚拟线程跑，墙钟时间从「N × 单次」降到约等于「单次」。
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (AnalysisReport.QuestionReview review : reviews) {
                if (review.getQuestion() == null || review.getQuestion().isBlank()) {
                    continue;
                }
                futures.add(pool.submit(() -> {
                    try {
                        RetrievalResult r = knowledgeService.retrieveWithSignals(
                                coverageQuery(record, review.getQuestion()), 3);
                        review.setNoteSupported(r.isConfident());
                        review.setNoteHint(noteHint(r));
                    } catch (Exception e) {
                        log.warn("笔记覆盖度判定失败，该题不标注：{}", e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(COVERAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // 单题判定不该拖垮整份报告：超时就取消，该题 noteSupported 保持 null
                    f.cancel(true);
                    log.warn("笔记覆盖度判定超时，该题不标注：{}", e.toString());
                }
            }
        }
    }

    /**
     * 覆盖度判定用的检索词 = 题目原文 + 该题标签。
     * 带上标签是因为标签是精确的技术词（如 redis-cache、mysql-index-fail），
     * 能显著提升关键词通道的命中质量。
     */
    private String coverageQuery(InterviewRecord record, String question) {
        for (InterviewQuestion q : record.getQuestions()) {
            if (question.equals(q.getQuestion()) && q.getTags() != null && !q.getTags().isBlank()) {
                return question + " " + q.getTags();
            }
        }
        return question;
    }

    /** 把判定结果翻译成用户能直接照做的建议 */
    private String noteHint(RetrievalResult r) {
        String title = r.getResults().isEmpty() ? null : r.getResults().get(0).getTitle();
        if (r.isConfident()) {
            return title == null || title.isBlank()
                    ? "你的笔记里有相关内容 —— 这不是知识缺口，建议重新消化笔记"
                    : "你的笔记里已有《" + title + "》—— 这不是知识缺口，建议重新消化笔记";
        }
        return "你的笔记里没有这块内容 —— 这是知识缺口，建议补一篇笔记";
    }

    @Override
    public InterviewRecord loadRecordWithQuestions(Long recordId) {
        Long uid = userContext.currentUserId().orElse(null);
        return (uid == null
                ? recordRepository.findById(recordId)
                : recordRepository.findByIdWithQuestionsAndUserId(recordId, uid))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试记录不存在: " + recordId));
    }

    @Override
    public void saveAnalysis(Long recordId, AnalysisReport report) {
        InterviewRecord record = loadRecordWithQuestions(recordId);
        saveAnalysis(record, report);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalysisHistoryItem> history(Long recordId) {
        loadRecordWithQuestions(recordId);
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

    @Override
    @Transactional(readOnly = true)
    public List<AnalysisRecordDto> all() {
        Long uid = userContext.currentUserId().orElse(null);
        var list = uid == null ? analysisRepository.findAll() : analysisRepository.findByUserIdOrderByCreatedAtDesc(uid);
        return list.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisRecordDto getAnalysis(Long analysisId) {
        Long uid = userContext.currentUserId().orElse(null);
        InterviewAnalysis a = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "分析报告不存在: " + analysisId));
        if (uid != null && !uid.equals(a.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析报告不存在: " + analysisId);
        }
        return toDto(a);
    }

    @Override
    public String buildUserPrompt(InterviewRecord r) {
        return buildUserPrompt(r, null);
    }

    /**
     * 组装喂给模型的上下文，三段各自计时：
     * <ul>
     *   <li>{@code prompt.base} —— 纯字符串拼接，理论上是微秒级；一旦不是，说明题目条数失控</li>
     *   <li>{@code prompt.rag} —— 知识库检索：一次 embedding + 一次向量/关键词检索，
     *       <b>这一段受 Ollama 是否在显存里直接影响</b>（模型冷加载是秒级）</li>
     *   <li>{@code prompt.memory} —— 历史复盘注入：读库 + 反序列化若干份报告 JSON</li>
     * </ul>
     * 三段都发生在模型调用<b>之前</b>，都会被计入端到端、但不会计入 llm_call_duration ——
     * 这正是此前「端到端 12.7s 而模型仅 6.3s，差的 6 秒说不清在哪」的盲区。
     *
     * @param t 计时器，传 null 表示不计时（流式分析、外部调用场景）
     */
    private String buildUserPrompt(InterviewRecord r, StageRecorder t) {
        String base = doBuildUserPrompt(r);
        if (t != null) {
            t.mark("prompt.base");
        }
        String knowledge = retrieveKnowledgeContext(r);
        if (t != null) {
            t.mark("prompt.rag");
        }
        String memory = memoryService.recentInsights(r.getUserId(), r.getId(), 3);
        if (t != null) {
            t.mark("prompt.memory");
        }
        return base + knowledge + memory;
    }

    @Override
    public Flux<String> streamAnalysis(Long recordId) {
        InterviewRecord record = loadForAnalysis(recordId);
        return llmService.stream(STREAM_SYSTEM_PROMPT, buildUserPrompt(record));
    }

    private String analysisCacheKey(InterviewRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl2(r.getPosition())).append('|').append(nvl2(r.getJd())).append('|');
        for (InterviewQuestion q : r.getQuestions()) {
            sb.append(nvl2(q.getQuestion())).append('|').append(nvl2(q.getMyAnswer())).append('|')
              .append(nvl2(q.getFeedback())).append('|').append(q.getScore()).append('|');
        }
        return "analysis:" + PROMPT_VERSION + ":" + r.getUserId() + ":" + r.getId()
                + ":" + Digest.sha256Short(sb.toString());
    }

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

    private void saveAnalysis(InterviewRecord record, AnalysisReport report) {
        try {
            InterviewAnalysis entity = new InterviewAnalysis();
            entity.setRecord(record);
            entity.setModel(llmService.mode());
            entity.setUserId(record.getUserId());
            entity.setSummary(report.getSummary());
            entity.setReportJson(objectMapper.writeValueAsString(report));
            analysisRepository.save(entity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分析报告序列化失败: " + e.getMessage(), e);
        }
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
