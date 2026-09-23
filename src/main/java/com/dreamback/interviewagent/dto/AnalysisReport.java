package com.dreamback.interviewagent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 面试复盘报告（LLM 结构化输出 / 接口响应共用）。 */
@Data
public class AnalysisReport {

    @JsonPropertyDescription("一句话总评")
    private String summary;

    @JsonPropertyDescription("答得好的问题列表")
    private List<QuestionReview> goodQuestions = new ArrayList<>();

    @JsonPropertyDescription("答得差的问题列表")
    private List<QuestionReview> weakQuestions = new ArrayList<>();

    @JsonPropertyDescription("暴露出的知识盲区")
    private List<String> knowledgeGaps = new ArrayList<>();

    @JsonPropertyDescription("下次优先补强的 3 个点，每个点必须给出练习建议")
    private List<FocusPoint> topPriorities = new ArrayList<>();

    @Data
    public static class QuestionReview {
        @JsonPropertyDescription("问题原文")
        private String question;

        @JsonPropertyDescription("判断理由")
        private String reason;
    }

    @Data
    public static class FocusPoint {
        @JsonPropertyDescription("需要补强的知识点")
        private String point;

        @JsonPropertyDescription("具体练习建议，要可执行")
        private String practiceAdvice;
    }
}
