package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.InterviewQuestion;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Long> {

    /** 按主题检索历史弱项：匹配标签、题目原文或已回写的 weak_points */
    @Query("select q from InterviewQuestion q "
            + "where q.weakPoints is not null and q.weakPoints <> '' "
            + "and (lower(coalesce(q.tags, '')) like lower(concat('%', :topic, '%')) "
            + "  or lower(coalesce(q.question, '')) like lower(concat('%', :topic, '%')) "
            + "  or lower(coalesce(q.weakPoints, '')) like lower(concat('%', :topic, '%'))) "
            + "order by q.id desc")
    List<InterviewQuestion> searchWeakPoints(@Param("topic") String topic, Pageable pageable);
}
