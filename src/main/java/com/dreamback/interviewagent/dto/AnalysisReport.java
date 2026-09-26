package com.dreamback.interviewagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

        /**
         * 这道题的知识点，用户自己的笔记里有没有覆盖。
         *
         * <p>由后端检索笔记后计算（不是 LLM 输出）—— 标 READ_ONLY 让结构化输出的反序列化跳过它，
         * 避免模型编一个值覆盖真实结果。null = 未判断（知识库不可用 / 非错题）。
         */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        private Boolean noteSupported;

        /**
         * 给用户看的补强提示，把「答错」拆成两种完全不同的动作：
         * 笔记里有 → 重新消化；笔记里没有 → 补一篇（这才是知识缺口）。
         */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        private String noteHint;
    }

    @Data
    public static class FocusPoint {
        @JsonPropertyDescription("需要补强的知识点")
        private String point;

        @JsonPropertyDescription("具体练习建议，要可执行")
        private String practiceAdvice;
    }
}
