package com.dreamback.interviewagent.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 取当前登录用户。业务层统一从这里拿 userId，不直接碰 SecurityContextHolder
 * —— 便于单测时替换，也避免 @Async / MQ 消费者里漏传上下文。
 */
@Component
public class UserContext {

    /** 未登录或 Security 关闭时返回 empty（调用方决定是拒绝还是走单用户模式） */
    public Optional<Long> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof AuthUser u) {
            return Optional.of(u.userId());
        }
        // 匿名 / 未认证
        if (principal instanceof String s && "anonymousUser".equals(s)) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 必须登录，否则抛异常 */
    public Long requireUserId() {
        return currentUserId()
                .orElseThrow(() -> new IllegalStateException("未获取到当前用户，请确认请求携带了有效的 Authorization 头"));
    }

    public Optional<String> currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof AuthUser u) {
            return Optional.of(u.username());
        }
        return Optional.empty();
    }

    /** 放入 SecurityContext 的 principal */
    public record AuthUser(Long userId, String username, String role) {
    }
}
