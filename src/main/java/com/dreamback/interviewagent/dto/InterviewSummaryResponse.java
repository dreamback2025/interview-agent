package com.dreamback.interviewagent.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class InterviewSummaryResponse {

    private Long id;
    private String company;
    private String position;
    private LocalDate interviewDate;
    private String result;
    private int questionCount;
    private LocalDateTime createdAt;
}
