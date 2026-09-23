package com.dreamback.interviewagent.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class InterviewDetailResponse {

    private Long id;
    private String company;
    private String position;
    private LocalDate interviewDate;
    private String jd;
    private String result;
    private String notes;
    private LocalDateTime createdAt;
    private List<QuestionView> questions = new ArrayList<>();

    @Data
    public static class QuestionView {
        private Long id;
        private String question;
        private String myAnswer;
        private String feedback;
        private Integer score;
        private String tags;
        private String weakPoints;
    }
}
