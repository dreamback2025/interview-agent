package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.KnowledgeDoc;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocRepository extends JpaRepository<KnowledgeDoc, Long> {

    List<KnowledgeDoc> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<KnowledgeDoc> findByDocIdAndUserId(String docId, Long userId);

    long countByUserId(Long userId);
}
