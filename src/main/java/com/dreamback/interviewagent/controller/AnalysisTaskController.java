package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.async.TaskDispatcher;
import com.dreamback.interviewagent.dto.AnalysisReport;
import com.dreamback.interviewagent.dto.TaskStatusResponse;
import com.dreamback.interviewagent.dto.TaskSubmitRequest;
import com.dreamback.interviewagent.dto.TaskSubmitResponse;
import com.dreamback.interviewagent.entity.AnalysisTask;
import com.dreamback.interviewagent.entity.TaskStatus;
import com.dreamback.interviewagent.repository.AnalysisTaskRepository;
import com.dreamback.interviewagent.repository.InterviewAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 异步分析任务。
 *
 * 同步接口（POST /api/interviews/analyze/{id}）仍然保留：
 * 演示、自检、以及本身很快的场景（命中缓存时）用同步更直接。
 * 真正耗时的首次分析走这里 —— 提交即返回，前端拿 taskId 轮询。
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis/tasks")
@RequiredArgsConstructor
public class AnalysisTaskController {

    private final TaskDispatcher dispatcher;
    private final AnalysisTaskRepository taskRepo;
    private final InterviewAnalysisRepository analysisRepo;
    private final ObjectMapper objectMapper;

    /** 提交任务：202 Accepted，不等模型返回 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskSubmitResponse submit(@Valid @RequestBody TaskSubmitRequest req) {
        AnalysisTask t = dispatcher.submit(req.recordId());
        log.info("任务已提交：taskId={} recordId={} 通道={}", t.getTaskId(), t.getRecordId(), t.getDispatchMode());
        return toSubmit(t);
    }

    /** 查任务状态；成功后直接带报告回来 */
    @GetMapping("/{taskId}")
    public TaskStatusResponse status(@PathVariable String taskId) {
        return toStatus(find(taskId));
    }

    /** 最近 20 条任务，可按记录过滤 */
    @GetMapping
    public List<TaskStatusResponse> list(@RequestParam(required = false) Long recordId) {
        List<AnalysisTask> tasks = recordId == null
                ? taskRepo.findAll()
                : taskRepo.findByRecordIdOrderByCreatedAtDesc(recordId);
        return tasks.stream()
                .sorted(Comparator.comparing(AnalysisTask::getId).reversed())
                .limit(20)
                .map(this::toStatus)
                .toList();
    }

    /** 失败任务重试：状态重置为 PENDING 后重新投递（taskId 不变） */
    @PostMapping("/{taskId}/retry")
    public TaskSubmitResponse retry(@PathVariable String taskId) {
        AnalysisTask t = find(taskId);
        if (t.getStatus() != TaskStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "只有失败的任务可以重试，当前状态：" + t.getStatus());
        }
        String channel = dispatcher.redispatch(taskId);
        log.info("任务重新投递：taskId={} 通道={}", taskId, channel);
        return toSubmit(find(taskId));
    }

    private AnalysisTask find(String taskId) {
        return taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
    }

    private TaskSubmitResponse toSubmit(AnalysisTask t) {
        return new TaskSubmitResponse(t.getTaskId(), t.getRecordId(),
                t.getStatus().name(), t.getDispatchMode(), t.getCreatedAt());
    }

    private TaskStatusResponse toStatus(AnalysisTask t) {
        AnalysisReport report = null;
        if (t.getAnalysisId() != null) {
            report = analysisRepo.findById(t.getAnalysisId())
                    .map(a -> {
                        try {
                            return objectMapper.readValue(a.getReportJson(), AnalysisReport.class);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .orElse(null);
        }
        return new TaskStatusResponse(t.getTaskId(), t.getRecordId(), t.getStatus().name(),
                t.getRetryCount(), t.getErrorMessage(), t.getAnalysisId(), report,
                t.getDispatchMode(), t.getCreatedAt(), t.getFinishedAt());
    }
}
