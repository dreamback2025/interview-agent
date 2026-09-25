package com.dreamback.interviewagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 异步分析任务。
 *
 * 为什么用 recordId 而不是 ManyToOne：任务表是「执行侧」的数据，
 * 消费者只需要按 id 取记录，不需要对象关系；避免懒加载给异步线程带来额外麻烦。
 */
@Getter
@Setter
@Entity
@Table(name = "analysis_task")
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外暴露的业务任务号（UUID），不直接暴露自增 id */
    @Column(name = "task_id", unique = true, nullable = false, length = 64)
    private String taskId;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    /** 已尝试次数：首次执行为 1 */
    @Column(name = "retry_count")
    private int retryCount;

    /** 失败原因（成功时为空） */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 成功后生成的报告 id，便于前端直接跳转回看 */
    @Column(name = "analysis_id")
    private Long analysisId;

    /** 投递方式：mq / pool（记录当时实际走的路径，便于排查） */
    @Column(name = "dispatch_mode", length = 10)
    private String dispatchMode;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
