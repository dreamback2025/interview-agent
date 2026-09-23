package com.dreamback.interviewagent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 开始一场模拟面试的返回：会话 id + 题目列表。 */
@Data
public class MockStartResponse {

    private Long sessionId;

    private List<MockQuestionSet.MockQuestion> questions = new ArrayList<>();

    @JsonPropertyDescription("提示信息")
    private String message;
}
