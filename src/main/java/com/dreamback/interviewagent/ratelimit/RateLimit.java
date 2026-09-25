package com.dreamback.interviewagent.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 Controller 方法上：按当前用户维度限流，保护 LLM 配额与成本。
 *
 * <p>维度 = userId + 方法签名（同用户调 analyze 和调 mock/start 各自独立计数）。
 * 免鉴权模式（未登录）下按 "anonymous" 维度统一计数。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 每分钟允许的请求数，默认 60（与 app.ratelimit.qpm 默认一致） */
    int qpm() default 60;
}
