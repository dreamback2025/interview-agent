package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.InterviewAnalysis;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewAnalysisRepository extends JpaRepository<InterviewAnalysis, Long> {

    List<InterviewAnalysis> findByRecordIdOrderByCreatedAtDesc(Long recordId);

    /** 会话记忆用：最近几次分析（不分记录） */
    List<InterviewAnalysis> findTop8ByOrderByCreatedAtDesc();
}
