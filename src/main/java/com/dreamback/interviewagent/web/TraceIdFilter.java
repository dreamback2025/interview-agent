package com.dreamback.interviewagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给每个请求打一个 traceId，贯穿日志 + 响应头。
 *
 * <p>放在 SecurityFilter 之前（HIGHEST_PRECEDENCE），这样 401/403 也能带上 traceId，
 * 排查未授权问题时能从日志反查到具体请求。
 *
 * <p>异步线程不会自动继承 MDC（ThreadLocal），{@link com.dreamback.interviewagent.async.TaskExecutor}
 * 会显式复制上下文。
 *
 * <p>客户端可在请求头传 X-Trace-Id 透传（用于跨服务调用链）；不传则生成短码。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader(HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);   // 必须 clear，线程池复用线程时不能串号
        }
    }
}
