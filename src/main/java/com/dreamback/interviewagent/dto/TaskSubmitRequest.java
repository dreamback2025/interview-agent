package com.dreamback.interviewagent.dto;

import jakarta.validation.constraints.NotNull;

/** 提交异步分析任务 */
public record TaskSubmitRequest(@NotNull(message = "recordId 不能为空") Long recordId) { }
