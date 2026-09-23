package com.dreamback.interviewagent.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 一场模拟面试的完整回放。 */
@Data
public class MockSessionDetail {

    private Long sessionId;
    private String targetJd;
    private String focus;
    private String status;
    private Integer totalScore;
    private String summary;
    private LocalDateTime createdAt;
    private List<TurnView> turns = new ArrayList<>();

    @Data
    public static class TurnView {
        private Long id;
        private int turnIndex;
        private String role;
        private String content;
        private Integer score;
        private String feedback;
    }
}
