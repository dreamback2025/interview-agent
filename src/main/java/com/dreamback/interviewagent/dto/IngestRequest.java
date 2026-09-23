package com.dreamback.interviewagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class IngestRequest {

    @NotBlank(message = "title 不能为空")
    private String title;

    /** 来源：博客 / 笔记 / 文档名 */
    private String source;

    /** 逗号分隔 */
    private String tags;

    @NotBlank(message = "content 不能为空")
    private String content;
}
