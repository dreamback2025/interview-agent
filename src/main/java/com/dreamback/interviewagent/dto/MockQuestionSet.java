package com.dreamback.interviewagent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 按目标 JD 生成的模拟题集合。 */
@Data
public class MockQuestionSet {

    @JsonPropertyDescription("模拟面试题列表")
    private List<MockQuestion> questions = new ArrayList<>();

    @Data
    public static class MockQuestion {
        @JsonPropertyDescription("题目")
        private String question;

        @JsonPropertyDescription("这道题考察的意图")
        private String intent;

        @JsonPropertyDescription("难度：简单/中等/困难")
        private String difficulty;

        @JsonPropertyDescription("作答提示，不要直接给答案")
        private String hint;
    }
}
