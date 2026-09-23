package com.dreamback.interviewagent.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 单条历史分析详情（含完整报告）。 */
@Data
public class AnalysisRecordDto {

    private Long id;
    private Long recordId;
    private String model;
    private LocalDateTime createdAt;
    private AnalysisReport report;
}
