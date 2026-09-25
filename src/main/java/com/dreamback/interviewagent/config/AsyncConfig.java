package com.dreamback.interviewagent.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 异步分析任务的线程池与消息队列声明。 */
@Configuration
public class AsyncConfig {

    /** 队列名，与投递/消费端保持一致 */
    public static final String QUEUE = "analysis.task.queue";

    @Bean
    public Queue analysisTaskQueue() {
        // durable=true：Broker 重启后队列不丢（消息持久化还要配合投递时的持久化模式）
        return new Queue(QUEUE, true);
    }

    /**
     * 无 MQ 时的执行通道。
     *
     * 拒绝策略用 CallerRunsPolicy：队列满了让提交线程自己跑，
     * 相当于把压力原路还给调用方（HTTP 请求变慢），而不是静默丢任务 —— 这是一种背压。
     */
    @Bean
    public ThreadPoolTaskExecutor analysisPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
