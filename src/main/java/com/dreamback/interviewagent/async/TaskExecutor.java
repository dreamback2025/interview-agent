package com.dreamback.interviewagent.async;

import com.dreamback.interviewagent.entity.InterviewAnalysis;
import com.dreamback.interviewagent.entity.TaskStatus;
import com.dreamback.interviewagent.repository.AnalysisTaskRepository;
import com.dreamback.interviewagent.repository.InterviewAnalysisRepository;
import com.dreamback.interviewagent.service.AnalysisService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务执行逻辑（与投递方式解耦）：无论是 MQ 消费者还是本地线程池，最终都走这里。
 *
 * 幂等保证在数据库层：claim 阶段用条件更新，重复投递时第二条消息抢不到 PENDING 状态，
 * 直接跳过 —— 不会把同一份内容分析两次，也就不会重复消耗 token。
 */
@Slf4j
@Service
public class TaskExecutor {

    private final AnalysisTaskRepository taskRepo;
    private final TaskStateService stateService;
    private final AnalysisService analysisService;
    private final InterviewAnalysisRepository analysisRepo;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter skippedCounter;

    public TaskExecutor(AnalysisTaskRepository taskRepo,
                        TaskStateService stateService,
                        AnalysisService analysisService,
                        InterviewAnalysisRepository analysisRepo,
                        MeterRegistry registry) {
        this.taskRepo = taskRepo;
        this.stateService = stateService;
        this.analysisService = analysisService;
        this.analysisRepo = analysisRepo;
        this.successCounter = Counter.builder("app.task.finished").tag("result", "success").register(registry);
        this.failureCounter = Counter.builder("app.task.finished").tag("result", "failure").register(registry);
        this.skippedCounter = Counter.builder("app.task.finished").tag("result", "skipped").register(registry);
    }

    public void execute(String taskId) {
        if (!stateService.claim(taskId)) {
            // 重复投递或任务已被取消 —— 幂等跳过
            skippedCounter.increment();
            log.info("任务已处理过，跳过重复执行：taskId={}", taskId);
            return;
        }

        Long recordId = taskRepo.findByTaskId(taskId).map(t -> t.getRecordId()).orElse(null);
        if (recordId == null) {
            return;
        }

        try {
            analysisService.analyze(recordId);
            Long analysisId = analysisRepo.findByRecordIdOrderByCreatedAtDesc(recordId)
                    .stream()
                    .findFirst()
                    .map(InterviewAnalysis::getId)
                    .orElse(null);
            stateService.finish(taskId, TaskStatus.SUCCESS, analysisId, null);
            successCounter.increment();
            log.info("异步分析完成：taskId={} recordId={}", taskId, recordId);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            stateService.finish(taskId, TaskStatus.FAILED, null, msg);
            failureCounter.increment();
            log.warn("异步分析失败：taskId={} 原因={}", taskId, msg);
        }
    }
}
