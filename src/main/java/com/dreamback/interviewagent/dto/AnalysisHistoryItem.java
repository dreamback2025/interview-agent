package com.dreamback.interviewagent.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 历史分析列表项（不含报告全文，避免列表过大）。 */
@Data
public class AnalysisHistoryItem {

    private Long id;
    private Long recordId;
    private String model;
    private String summary;
    private LocalDateTime createdAt;
}
