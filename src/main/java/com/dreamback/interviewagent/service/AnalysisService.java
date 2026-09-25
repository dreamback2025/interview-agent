package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.AnalysisHistoryItem;
import com.dreamback.interviewagent.dto.AnalysisRecordDto;
import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.entity.InterviewRecord;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 面试复盘分析服务：录入 → LLM 分析弱项 → 输出补强建议 → 报告落库。
 *
 * <p>实现见 {@link com.dreamback.interviewagent.service.impl.AnalysisServiceImpl}。
 * 含「按用户隔离的记录加载」与「Prompt 拼装」两个被 Agent 模式复用的方法。
 */
public interface AnalysisService {

    /** 分析一条面试记录；命中缓存则跳过模型调用（幂等） */
    AnalysisReport analyze(Long recordId);

    /** 按「记录 id + 当前用户」加载；越权视为不存在。Agent 模式也复用 */
    InterviewRecord loadRecordWithQuestions(Long recordId);

    /** 供 Agent 模式复用：只落库，不重复分析 */
    void saveAnalysis(Long recordId, AnalysisReport report);

    /** 某条面试的历史分析列表（先校验记录归属） */
    List<AnalysisHistoryItem> history(Long recordId);

    /** 当前用户可见的全部分析报告（导出用） */
    List<AnalysisRecordDto> all();

    /** 回看单份分析报告；越权返回 404 */
    AnalysisRecordDto getAnalysis(Long analysisId);

    /** 拼装 user prompt（含 RAG 上下文 + 历史记忆） */
    String buildUserPrompt(InterviewRecord r);

    /** 流式复盘：Markdown 逐段推送，不落库（要存档请用 {@link #analyze}） */
    Flux<String> streamAnalysis(Long recordId);
}
