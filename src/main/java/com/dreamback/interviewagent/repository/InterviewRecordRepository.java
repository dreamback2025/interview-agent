package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.InterviewRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewRecordRepository extends JpaRepository<InterviewRecord, Long> {

    @EntityGraph(attributePaths = "questions")
    @Query("select r from InterviewRecord r where r.id = :id and r.userId = :userId")
    Optional<InterviewRecord> findByIdWithQuestionsAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    List<InterviewRecord> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);
}
