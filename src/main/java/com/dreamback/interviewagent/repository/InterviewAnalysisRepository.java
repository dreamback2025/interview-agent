package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.InterviewAnalysis;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InterviewAnalysisRepository extends JpaRepository<InterviewAnalysis, Long> {

    List<InterviewAnalysis> findByRecordIdOrderByCreatedAtDesc(Long recordId);

    List<InterviewAnalysis> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 会话记忆用：该用户最近几次分析（跨记录，但绝不跨用户） */
    List<InterviewAnalysis> findTop8ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 免鉴权（单用户）模式：不分用户取最近几条 */
    @Query("select a from InterviewAnalysis a order by a.createdAt desc limit 8")
    List<InterviewAnalysis> findTop8ByOrderByCreatedAtDesc();
}
