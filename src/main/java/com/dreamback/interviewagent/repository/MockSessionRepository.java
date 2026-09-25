package com.dreamback.interviewagent.repository;

import com.dreamback.interviewagent.entity.MockSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MockSessionRepository extends JpaRepository<MockSession, Long> {

    /** 隔离：不是自己的会话查不到（返回 404，不泄露存在性） */
    Optional<MockSession> findByIdAndUserId(Long id, Long userId);
}
