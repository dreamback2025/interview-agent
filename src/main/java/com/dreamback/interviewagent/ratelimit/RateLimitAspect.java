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
    private final RateLimitProperties properties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        Long uid = userContext.currentUserId().orElse(null);
        String userKey = uid == null ? "anonymous" : "u" + uid;
        String method = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        String userDim = userKey + ":" + method;
        String globalDim = "global:" + method;

        RateLimitProperties.Rule rule = properties.ruleFor(method);
        int qpm = pick(rateLimit.qpm(), rule.getQpm());
        int maxInFlight = pick(rateLimit.maxInFlight(), rule.getMaxInFlight());
        int globalQpm = pick(rateLimit.globalQpm(), rule.getGlobalQpm());
        int globalInFlight = pick(rateLimit.globalInFlight(), rule.getGlobalInFlight());

        // ① 频率维度：全局优先。只有 per-user 是不够的 —— 每个用户都没超限，
        //    十个用户叠起来照样能把容量打穿。
        if (globalQpm > 0 && !rateLimitService.tryAcquire(globalDim, globalQpm)) {
            throw tooMany("系统繁忙，请稍后再试");
        }
        if (qpm > 0 && !rateLimitService.tryAcquire(userDim, qpm)) {
            throw tooMany("请求过于频繁，每分钟限 " + qpm + " 次，请稍后再试");
        }

        // ② 并发维度：这才是保护线程池/连接池的关键。拿到令牌后必须归还，见 finally。
        String globalToken = globalInFlight > 0
                ? rateLimitService.enter(globalDim, globalInFlight)
                : null;
        if (globalInFlight > 0 && globalToken == null) {
            throw tooMany("系统繁忙，请稍后再试");
        }
        String userToken = null;
        try {
            if (maxInFlight > 0) {
                userToken = rateLimitService.enter(userDim, maxInFlight);
                if (userToken == null) {
                    throw tooMany("你同时进行的请求太多（上限 " + maxInFlight
                            + " 个），请等当前任务结束后再试");
                }
            }
            return pjp.proceed();
        } finally {
            // 无论正常返回还是抛异常都要归还，否则名额会一直占到超时才被清理
            if (userToken != null) {
                rateLimitService.leave(userDim, userToken);
            }
            if (globalToken != null) {
                rateLimitService.leave(globalDim, globalToken);
            }
        }
    }

    /** 注解上写死的值优先（-1 表示「听配置的」），这样既有默认值可配，也保留单点覆盖能力 */
    private static int pick(int annotationValue, int configured) {
        return annotationValue >= 0 ? annotationValue : configured;
    }

    private static ResponseStatusException tooMany(String reason) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason);
    }
}
