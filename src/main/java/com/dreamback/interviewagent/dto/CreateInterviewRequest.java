package com.dreamback.interviewagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class CreateInterviewRequest {

    @NotBlank(message = "company 不能为空")
    @Size(max = 100)
    private String company;

    @NotBlank(message = "position 不能为空")
    @Size(max = 100)
    private String position;

    private LocalDate interviewDate;

    /** 目标 JD 原文，后续用于按 JD 出题/对照 */
    private String jd;

    /** 例如：通过 / 挂了 / 待结果 */
    private String result;

    private String notes;

    @Valid
    private List<QuestionInput> questions = new ArrayList<>();

    @Data
    public static class QuestionInput {
        @NotBlank(message = "question 不能为空")
        private String question;

        private String myAnswer;

        private String feedback;

        /** 0-10 自评或面试官打分 */
        private Integer score;

        /** 逗号分隔，例如：Java,并发,JVM */
        private String tags;
    }
}
