package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.MockSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MockSessionRepository extends JpaRepository<MockSession, Long> {
}
