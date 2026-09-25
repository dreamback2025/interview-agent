package com.dreamback.interviewagent.async;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 把提交线程的 MDC（主要是 traceId）复制到执行线程。
 *
 * <p>ThreadPoolTaskExecutor 用独立线程池，ThreadLocal 默认不继承 ——
 * 不装饰的话，异步任务里打的日志没有 traceId，排查时无法和提交请求关联。
 *
 * <p>执行完必须 clear，避免线程池复用线程时上下文串号。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在提交线程捕获上下文（此时还能拿到 traceId）
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
