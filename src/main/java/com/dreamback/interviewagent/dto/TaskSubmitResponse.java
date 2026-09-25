package com.dreamback.interviewagent.dto;

import java.time.LocalDateTime;

/** 任务提交结果：202 Accepted 立即返回，前端拿 taskId 轮询进度 */
public record TaskSubmitResponse(
        String taskId,
        Long recordId,
        String status,
        /** 实际走的执行通道：mq / pool（pool 说明发生了降级） */
        String dispatchMode,
        LocalDateTime createdAt) { }
