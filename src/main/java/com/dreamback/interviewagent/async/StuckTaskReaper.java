package com.dreamback.interviewagent.async;

import com.dreamback.interviewagent.entity.AnalysisTask;
import com.dreamback.interviewagent.entity.TaskStatus;
import com.dreamback.interviewagent.repository.AnalysisTaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 悬挂任务回收。
 *
 * <h3>为什么必须有</h3>
 * 任务投递的设计是「先落库为 PENDING，再发消息」—— 最坏情况不是丢任务，而是
 * <b>任务永远停在 PENDING</b>。以下几种情况都会留下悬挂任务：
 * <ul>
 *   <li>实例在「已落库、未发消息」之间崩溃；</li>
 *   <li>RabbitMQ 消息丢失（队列未持久化、Broker 重启）；</li>
 *   <li>本地线程池队列已满被拒绝（TaskDispatcher 记 pool_rejected，任务保持 PENDING）；</li>
 *   <li>消费者拿到消息后进程被 kill，任务卡在 RUNNING。</li>
 * </ul>
 * 没有回收机制，前端会一直轮询一个永远不会完成的任务。
 *
 * <h3>为什么用「扫描超时」而不是「监听失败事件」</h3>
 * 上面几种情况里，进程崩溃和消息丢失<b>根本没有失败事件可监听</b> —— 崩溃的实例不会通知谁。
 * 所以只能靠一个独立于任务本身的扫描者，按「状态 + 更新时间」判定悬挂。
 *
 * <h3>并发与幂等</h3>
 * 多实例部署时每个实例都会扫到同一批任务，可能重复重投。这里不额外加锁：
 * 重投的安全性由 {@link TaskExecutor} 的 claim（PENDING→RUNNING 条件更新）保证 ——
 * 同一任务的多次投递只会有一个抢占成功，其余全部跳过，不会重复烧 token。
 *
 * <p>⚠️ 超时阈值必须显著大于「单次分析的最大可能耗时」（模型超时 + 笔记检索）。
 * 设太小会把还在正常执行的任务误判为悬挂。
 */
@Slf4j
@Service
public class StuckTaskReaper {

    private final AnalysisTaskRepository taskRepo;
    private final TaskDispatcher dispatcher;
    private final TaskStateService stateService;
    private final int stuckTimeoutMinutes;
    private final int maxRetry;
    private final Counter requeuedCounter;
    private final Counter abandonedCounter;

    public StuckTaskReaper(AnalysisTaskRepository taskRepo,
                           TaskDispatcher dispatcher,
                           TaskStateService stateService,
                           MeterRegistry registry,
                           @Value("${app.async.stuck-timeout-minutes:10}") int stuckTimeoutMinutes,
                           @Value("${app.async.max-retry:2}") int maxRetry) {
        this.taskRepo = taskRepo;
        this.dispatcher = dispatcher;
        this.stateService = stateService;
        this.stuckTimeoutMinutes = stuckTimeoutMinutes;
        this.maxRetry = maxRetry;
        this.requeuedCounter = Counter.builder("app.task.reaped").tag("action", "requeue").register(registry);
        this.abandonedCounter = Counter.builder("app.task.reaped").tag("action", "abandon").register(registry);
    }

    @Scheduled(initialDelayString = "${app.async.reaper-initial-delay-ms:60000}",
               fixedDelayString = "${app.async.reaper-interval-ms:120000}")
    public void reap() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(stuckTimeoutMinutes);
        List<AnalysisTask> stuck = taskRepo.findByStatusInAndUpdatedAtBefore(
                List.of(TaskStatus.PENDING, TaskStatus.RUNNING), deadline);
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("发现 {} 个悬挂任务（超过 {} 分钟未结束），开始补偿", stuck.size(), stuckTimeoutMinutes);
        for (AnalysisTask t : stuck) {
            try {
                if (t.getRetryCount() >= maxRetry) {
                    // 已经试过足够多次，再投也是徒劳 —— 明确标记失败，让用户能看到原因并手动重试
                    stateService.finish(t.getTaskId(), TaskStatus.FAILED, null,
                            "执行超时（超过 " + stuckTimeoutMinutes + " 分钟未完成，已重试 "
                                    + t.getRetryCount() + " 次）");
                    abandonedCounter.increment();
                    log.warn("任务重试次数用尽，标记失败：taskId={} retry={}", t.getTaskId(), t.getRetryCount());
                } else {
                    String channel = dispatcher.redispatch(t.getTaskId(), t.getUserId());
                    requeuedCounter.increment();
                    log.warn("悬挂任务重新投递：taskId={} retry={} 通道={}",
                            t.getTaskId(), t.getRetryCount(), channel);
                }
            } catch (Exception e) {
                // 单个任务补偿失败不能影响同批其他任务
                log.warn("悬挂任务补偿失败：taskId={} 原因={}", t.getTaskId(), e.toString());
            }
        }
    }
}
