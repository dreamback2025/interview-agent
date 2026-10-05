package com.dreamback.interviewagent.resilience;

/**
 * 熔断器处于 OPEN（或半开探测名额已用完）时抛出。
 *
 * <p>抛出意味着 <b>受保护的调用一次都没执行</b> —— 这正是熔断的价值：
 * 不再把线程和连接浪费在一个已经判定为不可用的上游上。
 */
public class CircuitOpenException extends RuntimeException {

    private final String breakerName;
    private final long retryAfterMs;

    public CircuitOpenException(String breakerName, long retryAfterMs) {
        super("熔断器[" + breakerName + "]已开路，请 " + retryAfterMs + "ms 后重试");
        this.breakerName = breakerName;
        this.retryAfterMs = retryAfterMs;
    }

    public String getBreakerName() {
        return breakerName;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
