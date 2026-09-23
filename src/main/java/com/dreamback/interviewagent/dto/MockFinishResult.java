package com.dreamback.interviewagent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 整场模拟面试的总结。 */
@Data
public class MockFinishResult {

    @JsonPropertyDescription("总分，0-100")
    private Integer totalScore;

    @JsonPropertyDescription("总体评语")
    private String comment;

    @JsonPropertyDescription("答得好的地方")
    private List<String> strengths = new ArrayList<>();

    @JsonPropertyDescription("需要改进的地方")
    private List<String> improvements = new ArrayList<>();
}
