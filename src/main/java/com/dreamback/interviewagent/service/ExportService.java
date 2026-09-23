package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.ExportDto;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 全量导出：把面试记录与历史报告 dump 成人可读的 JSON。 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final InterviewRecordRepository recordRepository;
    private final InterviewService interviewService;
    private final AnalysisService analysisService;

    @Transactional(readOnly = true)
    public ExportDto exportAll() {
        var interviews = recordRepository.findAll().stream()
                .map(interviewService::toDetail)
                .toList();
        var analyses = analysisService.all();

        ExportDto dto = new ExportDto();
        dto.setExportedAt(LocalDateTime.now());
        dto.setRecordCount(interviews.size());
        dto.setAnalysisCount(analyses.size());
        dto.getInterviews().addAll(interviews);
        dto.getAnalyses().addAll(analyses);
        return dto;
    }
}
