package com.dreamback.interviewagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一次分析产生的完整报告快照（每次分析都会新增一条，保留历史）。 */
@Getter
@Setter
@Entity
@Table(name = "interview_analysis", indexes = {
        @Index(name = "idx_analysis_record", columnList = "record_id"),
        @Index(name = "idx_analysis_user_created", columnList = "user_id, created_at")
})
public class InterviewAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "record_id")
    private InterviewRecord record;

    /** 生成该报告的模型：deepseek / stub */
    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** 完整报告 JSON，便于回看与后续检索 */
    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;

    /** 数据隔离：冗余一份 userId，避免回看报告时还要 join 记录表 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
