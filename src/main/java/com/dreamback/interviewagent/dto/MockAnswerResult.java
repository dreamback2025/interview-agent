package com.dreamback.interviewagent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/** 单次作答的评判结果（LLM 结构化输出 / 接口响应共用）。 */
@Data
public class MockAnswerResult {

    @JsonPropertyDescription("本轮得分，0-100 的整数")
    private Integer score;

    @JsonPropertyDescription("针对这次回答的评价，要具体到缺了什么")
    private String feedback;

    @JsonPropertyDescription("追问。回答已经足够深入时留空字符串")
    private String followUp;

    /** true = 这道题问完了，可以进入下一题 */
    private boolean finished;
}
