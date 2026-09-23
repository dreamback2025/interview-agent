package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.dto.AnalysisHistoryItem;
import com.dreamback.interviewagent.dto.AnalysisRecordDto;
import com.dreamback.interviewagent.service.AnalysisService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 历史分析报告查询。 */
@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping
    public List<AnalysisHistoryItem> history(@RequestParam Long recordId) {
        return analysisService.history(recordId);
    }

    @GetMapping("/{id}")
    public AnalysisRecordDto detail(@PathVariable Long id) {
        return analysisService.getAnalysis(id);
    }
}
