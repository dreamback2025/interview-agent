package com.dreamback.interviewagent.async;

import com.dreamback.interviewagent.entity.AnalysisTask;
import com.dreamback.interviewagent.entity.TaskStatus;
import com.dreamback.interviewagent.repository.AnalysisTaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 任务投递。
 *
 * 顺序很重要：**先落库，再投递**。
 * 如果先发消息再写库，一旦写库失败，消息已经出去了 —— 消费者查不到任务，
 * 消息等于丢失且无法追踪。先落库则最坏情况是「任务一直 PENDING」，
 * 可以通过扫描 PENDING 超时任务来补偿。
 *
 * 投递通道可切换（app.async.mode）：
 *   mq   —— RabbitMQ，失败时自动降级为线程池
 *   pool —— 直接用本地线程池（无 MQ 环境）
 */
@Slf4j
@Service
public class TaskDispatcher {

    private final AnalysisTaskRepository taskRepo;
    private final TaskExecutor executor;
    private final ObjectProvider<AmqpTemplate> amqpProvider;
    private final ThreadPoolTaskExecutor pool;
    private final Counter mqCounter;
    private final Counter poolCounter;
    private final Counter fallbackCounter;

    @Value("${app.async.mode:mq}")
    private String mode;

    @Value("${app.async.queue:analysis.task.queue}")
    private String queueName;

    public TaskDispatcher(AnalysisTaskRepository taskRepo,
                          TaskExecutor executor,
                          ObjectProvider<AmqpTemplate> amqpProvider,
                          ThreadPoolTaskExecutor pool,
                          MeterRegistry registry) {
        this.taskRepo = taskRepo;
        this.executor = executor;
        this.amqpProvider = amqpProvider;
        this.pool = pool;
        this.mqCounter = Counter.builder("app.task.dispatched").tag("channel", "mq").register(registry);
        this.poolCounter = Counter.builder("app.task.dispatched").tag("channel", "pool").register(registry);
        this.fallbackCounter = Counter.builder("app.task.dispatched").tag("channel", "mq_fallback").register(registry);
    }

    public AnalysisTask submit(Long recordId) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setRecordId(recordId);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        AnalysisTask saved = taskRepo.save(task);

        String channel = dispatch(saved.getTaskId());
        saved.setDispatchMode(channel);
        return taskRepo.save(saved);
    }

    /** 失败任务重试：把状态重置为 PENDING 再走一次投递（taskId 保持不变，便于追踪） */
    public String redispatch(String taskId) {
        AnalysisTask task = taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        task.setStatus(TaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setFinishedAt(null);
        taskRepo.save(task);
        String channel = dispatch(taskId);
        task.setDispatchMode(channel);
        taskRepo.save(task);
        return channel;
    }

    private String dispatch(String taskId) {
        AmqpTemplate amqp = amqpProvider.getIfAvailable();
        if ("mq".equalsIgnoreCase(mode) && amqp != null) {
            try {
                amqp.convertAndSend(queueName, taskId);
                mqCounter.increment();
                return "mq";
            } catch (Exception e) {
                fallbackCounter.increment();
                log.warn("MQ 投递失败，降级为本地线程池执行：{}", e.toString());
            }
        }
        pool.execute(() -> executor.execute(taskId));
        poolCounter.increment();
        return "pool";
    }
}
