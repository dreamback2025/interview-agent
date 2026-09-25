package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.agent.AgentAnalysisService;
import com.dreamback.interviewagent.dto.CreateInterviewRequest;
import com.dreamback.interviewagent.dto.InterviewDetailResponse;
import com.dreamback.interviewagent.dto.InterviewSummaryResponse;
import com.dreamback.interviewagent.service.AnalysisService;
import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.ratelimit.RateLimit;
import com.dreamback.interviewagent.service.InterviewService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final AnalysisService analysisService;
    private final AgentAnalysisService agentAnalysisService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewDetailResponse create(@Valid @RequestBody CreateInterviewRequest req) {
        return interviewService.create(req);
    }

    @GetMapping
    public List<InterviewSummaryResponse> list() {
        return interviewService.list();
    }

    @GetMapping("/{id}")
    public InterviewDetailResponse detail(@PathVariable Long id) {
        return interviewService.get(id);
    }

    @PostMapping("/analyze/{recordId}")
    @RateLimit(qpm = 10)   // 烧 token 的重接口：每用户每分钟 10 次
    public AnalysisReport analyze(@PathVariable Long recordId) {
        return analysisService.analyze(recordId);
    }

    /** Agent 分析：模型自主调用工具的版本（失败自动降级为普通分析） */
    @PostMapping("/agent-analyze/{recordId}")
    @RateLimit(qpm = 10)   // 工具调用 + 分析，比普通分析更重
    public AnalysisReport agentAnalyze(@PathVariable Long recordId) {
        return agentAnalysisService.analyze(recordId);
    }

    /**
     * SSE 流式复盘。用 GET 是为了前端能直接用 EventSource / fetch 流式读取。
     */
    @GetMapping(value = "/{id}/analysis-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(qpm = 10)
    public Flux<String> analysisStream(@PathVariable Long id) {
        return analysisService.streamAnalysis(id);
    }
}
