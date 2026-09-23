package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.entity.InterviewAnalysis;
import com.dreamback.interviewagent.repository.InterviewAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话记忆：把「其他历次面试的复盘结论」注入当前分析。
 *
 * 为什么用数据库而不是 Redis：记忆内容本来就以报告形式持久化在 interview_analysis 表里，
 * 直接查即可，无需额外组件；Redis 只在「多实例共享缓存 / 高频读写」场景才有必要。
 * 想换成 Redis 时，只要替换本类的实现（对外只暴露 recentInsights）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryService {

    private final InterviewAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.memory.enabled:true}")
    private boolean enabled;

    /** 取最近若干次「其他记录」的结论；失败静默返回空串，绝不能拖垮分析 */
    @Transactional(readOnly = true)
    public String recentInsights(Long excludeRecordId, int limit) {
        if (!enabled) {
            return "";
        }
        try {
            List<InterviewAnalysis> list = analysisRepository.findTop8ByOrderByCreatedAtDesc();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (InterviewAnalysis a : list) {
                if (a.getRecord() != null && a.getRecord().getId().equals(excludeRecordId)) {
                    continue;
                }
                if (n++ >= limit) {
                    break;
                }
                sb.append("- ");
                if (a.getCreatedAt() != null) {
                    sb.append(a.getCreatedAt().toLocalDate()).append(' ');
                }
                if (a.getRecord() != null) {
                    sb.append('[').append(a.getRecord().getCompany()).append(' ')
                            .append(a.getRecord().getPosition()).append("] ");
                }
                sb.append(a.getSummary() == null ? "（无总评）" : a.getSummary());
                sb.append(gapsOf(a));
                sb.append('\n');
            }
            if (sb.length() == 0) {
                return "";
            }
            return "\n\n【历史记忆】该候选人之前几场面试的复盘结论。"
                    + "如果有老问题反复出现，请在报告中明确指出来，不要重复推荐已经补过的点：\n" + sb;
        } catch (Exception e) {
            log.warn("读取历史记忆失败，本次分析不携带记忆：{}", e.getMessage());
            return "";
        }
    }

    private String gapsOf(InterviewAnalysis a) {
        try {
            AnalysisReport rep = objectMapper.readValue(a.getReportJson(), AnalysisReport.class);
            if (rep.getKnowledgeGaps() == null || rep.getKnowledgeGaps().isEmpty()) {
                return "";
            }
            List<String> top = rep.getKnowledgeGaps().stream().limit(3).toList();
            return " ｜ 当时盲区：" + String.join("；", top);
        } catch (Exception e) {
            return "";
        }
    }
}
