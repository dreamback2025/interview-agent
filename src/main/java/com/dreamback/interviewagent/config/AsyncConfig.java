package com.dreamback.interviewagent.config;

import java.util.concurrent.ThreadPoolExecutor;
import com.dreamback.interviewagent.async.MdcTaskDecorator;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 异步分析任务的线程池与消息队列声明。 */
@Configuration
// 悬挂任务回收（StuckTaskReaper）依赖定时调度：任务可能因为实例崩溃或消息丢失
// 永远停在 PENDING，没有补偿机制前端就会一直轮询一个不可能完成的任务。
@EnableScheduling
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
     * <p><b>拒绝策略从 CallerRunsPolicy 改成 AbortPolicy</b>：CallerRuns 让提交线程自己跑任务，
     * 看起来是「不丢任务」，实际是把压力转嫁给调用方 —— 若是 HTTP 线程提交，用户请求会卡几十秒；
     * 若是 MQ 消费者线程提交，整个消费就停摆。而任务本身已经落库为 PENDING，
     * 拒绝不等于丢弃：TaskDispatcher 会捕获这次拒绝并保持 PENDING，交给补偿扫描重试。
     * 宁可让调用方立刻知道「忙」，也不要让它在无感知的情况下等死。
     */
    @Bean
    public ThreadPoolTaskExecutor analysisPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 有界且不宜过大：队列是「延迟」不是「吞吐」，攒 100 个任务 × 每个数十秒 = 用户等几十分钟
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 把提交线程的 traceId 复制到执行线程，异步任务日志也能和请求关联
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
