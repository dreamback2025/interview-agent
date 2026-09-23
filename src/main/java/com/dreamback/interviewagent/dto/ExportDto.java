package com.dreamback.interviewagent.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 全量导出快照：人可读、可备份、可迁移。 */
@Data
public class ExportDto {

    private LocalDateTime exportedAt;

    private int recordCount;

    private int analysisCount;

    private List<InterviewDetailResponse> interviews = new ArrayList<>();

    private List<AnalysisRecordDto> analyses = new ArrayList<>();
}
