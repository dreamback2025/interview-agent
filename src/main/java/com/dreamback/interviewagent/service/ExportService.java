package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.ExportDto;
import com.dreamback.interviewagent.repository.InterviewRecordRepository;
import com.dreamback.interviewagent.security.UserContext;
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
    private final UserContext userContext;

    @Transactional(readOnly = true)
    public ExportDto exportAll() {
        Long uid = userContext.currentUserId().orElse(null);
        var records = uid == null
                ? recordRepository.findAll()
                : recordRepository.findAllByUserIdOrderByCreatedAtDesc(uid);
        var interviews = records.stream()
                .map(interviewService::toDetail)
                .toList();
        // analysisService.all() 内部同样按当前用户过滤，导出不会带出别人的报告
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
