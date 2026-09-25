package com.dreamback.interviewagent.config;

import com.dreamback.interviewagent.entity.AppUser;
import com.dreamback.interviewagent.repository.AppUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动初始化：演示账号 + 存量数据归属迁移。
 *
 * <p>为什么要做迁移：项目原本是单用户无鉴权的，表里已有的数据 user_id 全是 NULL。
 * 直接开启鉴权会造成一个很隐蔽的事故 —— <b>老数据谁都查不到</b>（被隔离规则挡在外面），
 * 表现就是"升级后历史记录全没了"。所以启动时把无归属的数据归给最早创建的用户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    /** 需要补 userId 的表（interview_question / mock_turn 通过父实体隔离，不单独存） */
    private static final List<String> OWNED_TABLES = List.of(
            "interview_record", "interview_analysis", "knowledge_doc", "mock_session", "analysis_task");

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    @Value("${app.demo-user.enabled:true}")
    private boolean demoUserEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (demoUserEnabled) {
            ensureDemoUser();
        }
        migrateLegacyOwnership();
    }

    private void ensureDemoUser() {
        if (userRepository.existsByUsername("demo")) {
            return;
        }
        AppUser u = new AppUser();
        u.setUsername("demo");
        u.setPasswordHash(passwordEncoder.encode("demo123"));
        u.setDisplayName("演示账号");
        u.setRole("USER");
        userRepository.save(u);
        log.info("已创建演示账号：demo / demo123（生产环境请设置 DEMO_USER_ENABLED=false 关闭）");
    }

    /** 把 user_id 为空的历史数据归给最早创建的用户 */
    private void migrateLegacyOwnership() {
        Long owner = userRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .findFirst()
                .map(AppUser::getId)
                .orElse(null);
        if (owner == null) {
            log.info("暂无用户，跳过存量数据归属迁移");
            return;
        }

        int total = 0;
        for (String table : OWNED_TABLES) {
            try {
                int n = jdbc.update("UPDATE " + table + " SET user_id = ? WHERE user_id IS NULL", owner);
                if (n > 0) {
                    log.info("存量数据归属迁移：{} 表 {} 行 → userId={}", table, n, owner);
                    total += n;
                }
            } catch (Exception e) {
                // 表还不存在（比如该功能未使用过）属正常情况
                log.debug("跳过表 {}：{}", table, e.getMessage());
            }
        }

        // 向量库的 metadata 也要补上 userId，否则开了过滤条件后老 chunk 永远检索不到。
        // jsonb 合并语法只有 PostgreSQL 支持，其他库（H2）直接跳过。
        try {
            int n = jdbc.update("UPDATE vector_store SET metadata = metadata || "
                    + "jsonb_build_object('userId', ?::bigint) "
                    + "WHERE metadata->>'userId' IS NULL", owner);
            if (n > 0) {
                log.info("存量向量归属迁移：vector_store {} 行 → userId={}", n, owner);
                total += n;
            }
        } catch (Exception e) {
            log.debug("跳过 vector_store metadata 迁移：{}", e.getMessage());
        }

        if (total == 0) {
            log.info("存量数据归属迁移：无需处理（没有 user_id 为空的数据）");
        }
    }
}
