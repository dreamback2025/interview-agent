package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.AnalysisTask;
import com.dreamback.interviewagent.entity.TaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {

    Optional<AnalysisTask> findByTaskId(String taskId);

    List<AnalysisTask> findByRecordIdOrderByCreatedAtDesc(Long recordId);

    /** 积压监控：还没结束的任务数 */
    long countByStatusIn(List<TaskStatus> statuses);

    /**
     * 条件更新：只有处于 from 状态才能迁移到 to 状态。
     *
     * 这是消费端幂等的关键 —— 消息重复投递时，第二次的状态已经不是 PENDING，
     * 返回 0 行就直接跳过，不会把同一个任务分析两遍（也就不会重复烧 token）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AnalysisTask t SET t.status = :to, t.retryCount = t.retryCount + 1, "
            + "t.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE t.taskId = :taskId AND t.status = :from")
    int tryMove(@Param("taskId") String taskId,
                @Param("from") TaskStatus from,
                @Param("to") TaskStatus to);
}
