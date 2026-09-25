package com.dreamback.interviewagent.agent;

import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.entity.InterviewRecord;
import com.dreamback.interviewagent.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Agent 模式：模型自主决定要不要调用工具（检索知识库 / 查历史弱项 / 提炼 JD 要求）。
 * 与 AnalysisService 的区别：那边是我们先检索好再塞进 Prompt，这边是模型自己按需调用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentAnalysisService {

    private static final String SYSTEM_PROMPT = """
            你是一名资深面试复盘教练。你可以按需调用工具来补充信息：
            - searchKnowledgeBase：查候选人自己的笔记里有没有覆盖某个知识点；
            - searchWeakPoints：查历史上这个方向是不是已经暴露过弱项（判断是否老问题重犯）；
            - getJdRequirements：从 JD 提炼关键技术要求。
            工具能显著提升判断质量，该用就用；工具报错时不要纠结，直接基于已有信息给出结论。
            最后输出结构化复盘报告。全部使用中文。
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final InterviewTools interviewTools;
    private final AnalysisService analysisService;

    public AnalysisReport analyze(Long recordId) {
        // 复用 AnalysisService 的加载逻辑：同样按当前用户隔离，越权访问返回 404
        InterviewRecord record = analysisService.loadRecordWithQuestions(recordId);

        try {
            AnalysisReport report = chatClientBuilder
                    .defaultTools(interviewTools)
                    .build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(analysisService.buildUserPrompt(record))
                    .call()
                    .entity(AnalysisReport.class);

            if (report == null) {
                throw new IllegalStateException("模型未返回结构化结果");
            }
            analysisService.saveAnalysis(recordId, report);
            return report;
        } catch (Exception e) {
            // 工具调用/结构化输出失败时，退回不带工具的普通分析，保证功能可用
            log.warn("Agent 分析失败，降级为普通分析：{}", e.getMessage());
            return analysisService.analyze(recordId);
        }
    }
}
