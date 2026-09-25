package com.dreamback.interviewagent.ratelimit;

import com.dreamback.interviewagent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 限流切面：拦截标注了 {@link RateLimit} 的方法，按「userId + 方法」维度计数。
 *
 * <p>放行/拒绝的判断完全委托给 {@link RateLimitService}（Redis ZSET + Lua），
 * 切面只负责拼 key 与抛 429。
 *
 * <p>切面执行顺序：Spring Security filter 链 → DispatcherServlet → Controller AOP。
 * 因此进入切面时 SecurityContext 已填充，能取到当前用户。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ratelimit.enabled", havingValue = "true")
public class RateLimitAspect {

    private final RateLimitService rateLimitService;
    private final UserContext userContext;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        // 维度：用户 + 类短名 + 方法名。同用户调不同接口各算各的
        Long uid = userContext.currentUserId().orElse(null);
        String userKey = uid == null ? "anonymous" : "u" + uid;
        String method = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        String dimension = userKey + ":" + method;

        if (!rateLimitService.tryAcquire(dimension, rateLimit.qpm())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "请求过于频繁，每分钟限 " + rateLimit.qpm() + " 次，请稍后再试");
        }
        return pjp.proceed();
    }
}
