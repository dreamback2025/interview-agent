package com.dreamback.interviewagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一场模拟面试。 */
@Getter
@Setter
@Entity
@Table(name = "mock_session")
public class MockSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_jd", columnDefinition = "TEXT")
    private String targetJd;

    @Column(name = "focus", length = 100)
    private String focus;

    /** ACTIVE / FINISHED */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "total_score")
    private Integer totalScore;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
