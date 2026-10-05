package com.dreamback.interviewagent.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 Controller 方法上：限流。
 *
 * <p>四个维度，按需组合：
 * <ul>
 *   <li>{@link #qpm()} —— 单用户每分钟次数。控的是「频率」，防脚本刷接口。</li>
 *   <li>{@link #maxInFlight()} —— 单用户同时在飞数。<b>这才是保护线程池/连接池的正确维度</b>：
 *       频率限的是速率，而真正占资源的是「同时有几个在跑」；一次调用 25 秒时 20 qpm 意味着 8 个并发，
 *       上游一慢到 50 秒就变成 16 个 —— 用频率间接控并发，等于假设耗时恒定，而耗时由上游决定。</li>
 *   <li>{@link #globalQpm()} / {@link #globalInFlight()} —— 全局维度。只有 per-user 维度不够：
 *       每个用户都没超限，N 个用户叠起来照样把系统打爆。</li>
 * </ul>
 *
 * <p><b>阈值不写在注解里</b>：四个值默认都是 {@code -1}，表示「取 {@code app.ratelimit} 配置」
 * （先按方法名匹配 {@code rules}，没有则用 {@code defaults}）。写在注解上的值只用于少数需要
 * 显式覆盖的场合 —— 否则调一次限流阈值就得改代码重新发版。
 * 值 {@code 0} 表示该维度不限制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 单用户每分钟请求数。-1 = 取配置；0 = 不限；>0 = 覆盖配置 */
    int qpm() default -1;

    /** 单用户同时在飞数。-1 = 取配置；0 = 不限；>0 = 覆盖配置 */
    int maxInFlight() default -1;

    /** 全局每分钟总次数。-1 = 取配置；0 = 不限；>0 = 覆盖配置 */
    int globalQpm() default -1;

    /** 全局同时在飞数。-1 = 取配置；0 = 不限；>0 = 覆盖配置 */
    int globalInFlight() default -1;
}
