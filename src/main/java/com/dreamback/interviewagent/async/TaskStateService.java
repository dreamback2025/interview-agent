package com.dreamback.interviewagent.async;

import com.dreamback.interviewagent.entity.TaskStatus;
import com.dreamback.interviewagent.repository.AnalysisTaskRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务状态迁移。
 *
 * 每个方法都用 REQUIRES_NEW：状态变更必须独立提交，不能被业务事务（分析过程）拖着回滚
 * —— 否则分析一失败，连「这次失败了」这个事实都记不下来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStateService {

    private final AnalysisTaskRepository taskRepo;

    /** 抢占任务：只有 PENDING 能变成 RUNNING，返回 false 说明已被别人处理（重复投递） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String taskId) {
        return taskRepo.tryMove(taskId, TaskStatus.PENDING, TaskStatus.RUNNING) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String taskId, TaskStatus status, Long analysisId, String error) {
        taskRepo.findByTaskId(taskId).ifPresent(t -> {
            t.setStatus(status);
            if (analysisId != null) {
                t.setAnalysisId(analysisId);
            }
            if (error != null) {
                t.setErrorMessage(error.length() > 900 ? error.substring(0, 900) : error);
            }
            t.setFinishedAt(LocalDateTime.now());
            taskRepo.save(t);
        });
    }
}
