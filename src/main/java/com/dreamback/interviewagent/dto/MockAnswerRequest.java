package com.dreamback.interviewagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MockAnswerRequest {

    @NotNull(message = "sessionId 不能为空")
    private Long sessionId;

    /** 第几题（从 0 开始） */
    private int questionIndex;

    @NotBlank(message = "answer 不能为空")
    private String answer;
}
