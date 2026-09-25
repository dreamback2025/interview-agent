package com.dreamback.interviewagent.async;

import com.dreamback.interviewagent.config.AsyncConfig;
import com.dreamback.interviewagent.web.TraceIdFilter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * MQ 消费者：只负责「收到任务号 → 交给执行器」，不写业务逻辑。
 *
 * <p>幂等不在这里做，而在 TaskExecutor 的 claim 阶段（数据库条件更新）——
 * 因为消息重复投递、消费端重连重放都可能触发重复执行，
 * 只有数据库层的条件更新能真正挡住重复分析。
 *
 * <p>MQ 消费者不在 HTTP 请求线程，没有 traceId —— 这里自己生成一个，
 * 让异步任务的日志也能通过 traceId 串联（与提交请求的 traceId 不同，但任务内日志可关联）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisTaskConsumer {

    private final TaskExecutor executor;

    @RabbitListener(queues = AsyncConfig.QUEUE)
    public void onMessage(String taskId) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TraceIdFilter.MDC_KEY, traceId);
        try {
            log.debug("收到分析任务：taskId={}", taskId);
            executor.execute(taskId);
        } finally {
            MDC.clear();
        }
    }
}
