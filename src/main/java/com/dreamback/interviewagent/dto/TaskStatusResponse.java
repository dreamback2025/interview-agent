package com.dreamback.interviewagent.dto;

import java.time.LocalDateTime;

/** 任务状态与结果 */
public record TaskStatusResponse(
        String taskId,
        Long recordId,
        String status,
        int retryCount,
        String errorMessage,
        Long analysisId,
        /** 成功后直接带上报告，省掉前端再发一次请求 */
        AnalysisReport report,
        String dispatchMode,
        LocalDateTime createdAt,
        LocalDateTime finishedAt) { }
