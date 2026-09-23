package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.MockTurn;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MockTurnRepository extends JpaRepository<MockTurn, Long> {

    List<MockTurn> findBySessionIdOrderByTurnIndexAscIdAsc(Long sessionId);

    List<MockTurn> findBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);
}
