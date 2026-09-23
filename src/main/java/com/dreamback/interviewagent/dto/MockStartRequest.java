package com.dreamback.interviewagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MockStartRequest {

    @NotBlank(message = "jd 不能为空")
    private String jd;

    @Min(1)
    @Max(10)
    private int count = 5;

    /** 可选：希望重点考察的方向，例如「并发」「MySQL 索引」 */
    private String focus;
}
