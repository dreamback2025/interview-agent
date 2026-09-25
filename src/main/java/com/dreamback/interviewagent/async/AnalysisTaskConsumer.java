package com.dreamback.interviewagent.async;

import com.dreamback.interviewagent.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * MQ 消费者：只负责「收到任务号 → 交给执行器」，不写业务逻辑。
 *
 * 幂等不在这里做，而在 TaskExecutor 的 claim 阶段（数据库条件更新）——
 * 因为消息重复投递、消费端重连重放都可能触发重复执行，
 * 只有数据库层的条件更新能真正挡住重复分析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisTaskConsumer {

    private final TaskExecutor executor;

    @RabbitListener(queues = AsyncConfig.QUEUE)
    public void onMessage(String taskId) {
        log.debug("收到分析任务：taskId={}", taskId);
        executor.execute(taskId);
    }
}
