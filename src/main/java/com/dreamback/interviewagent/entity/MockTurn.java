package com.dreamback.interviewagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 模拟面试的一轮对话：role = INTERVIEWER（题目/追问）或 CANDIDATE（回答）。 */
@Getter
@Setter
@Entity
@Table(name = "mock_turn")
public class MockTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mock_session_id")
    private MockSession session;

    /** 第几题（同一题的追问复用同一个 turnIndex） */
    @Column(name = "turn_index")
    private int turnIndex;

    @Column(name = "role", length = 20)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 候选人这一轮的得分（0-100），仅 CANDIDATE 轮有值 */
    @Column(name = "score")
    private Integer score;

    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
