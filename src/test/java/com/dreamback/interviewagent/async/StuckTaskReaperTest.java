package com.dreamback.interviewagent.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dreamback.interviewagent.entity.AnalysisTask;
import com.dreamback.interviewagent.entity.TaskStatus;
import com.dreamback.interviewagent.repository.AnalysisTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StuckTaskReaperTest {

    private final AnalysisTaskRepository taskRepo = mock(AnalysisTaskRepository.class);
    private final TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    private final TaskStateService stateService = mock(TaskStateService.class);

    private StuckTaskReaper reaper(int maxRetry) {
        return new StuckTaskReaper(taskRepo, dispatcher, stateService,
                new SimpleMeterRegistry(), 10, maxRetry);
    }

    private static AnalysisTask task(String id, TaskStatus status, int retry) {
        AnalysisTask t = new AnalysisTask();
        t.setTaskId(id);
        t.setStatus(status);
        t.setRetryCount(retry);
        t.setUserId(7L);
        t.setUpdatedAt(LocalDateTime.now().minusMinutes(30));
        return t;
    }

    @Test
    void 没有悬挂任务时什么都不做() {
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any())).thenReturn(List.of());
        reaper(2).reap();
        verify(dispatcher, never()).redispatch(any(), any());
        verify(stateService, never()).finish(any(), any(), any(), any());
    }

    @Test
    void 未超上限的悬挂任务会被重新投递() {
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenReturn(List.of(task("t1", TaskStatus.PENDING, 0)));
        reaper(2).reap();

        verify(dispatcher).redispatch("t1", 7L);
        verify(stateService, never()).finish(any(), any(), any(), any());
    }

    @Test
    void 超过重试上限的任务标记失败而不是无限重投() {
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenReturn(List.of(task("t2", TaskStatus.RUNNING, 2)));
        reaper(2).reap();

        verify(dispatcher, never()).redispatch(any(), any());
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(stateService).finish(eq("t2"), eq(TaskStatus.FAILED), eq(null), msg.capture());
        assertThat(msg.getValue()).contains("超时");
    }

    @Test
    void 单个任务补偿失败不影响同批其他任务() {
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenReturn(List.of(task("bad", TaskStatus.PENDING, 0), task("good", TaskStatus.PENDING, 0)));
        when(dispatcher.redispatch("bad", 7L)).thenThrow(new IllegalArgumentException("任务不存在"));
        reaper(2).reap();

        verify(dispatcher).redispatch("good", 7L);
        assertThat(true).isTrue();   // 走到这里说明循环没有被第一个任务的异常打断
    }

    @Test
    void 扫描的是未结束状态且按超时时间过滤() {
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any())).thenReturn(List.of());
        reaper(2).reap();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskStatus>> statuses = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskRepo).findByStatusInAndUpdatedAtBefore(statuses.capture(), deadline.capture());

        assertThat(statuses.getValue()).containsExactly(TaskStatus.PENDING, TaskStatus.RUNNING);
        // deadline 应该是「10 分钟前」，而不是某个很久以前的时间
        assertThat(deadline.getValue()).isAfter(LocalDateTime.now().minusMinutes(11));
        assertThat(deadline.getValue()).isBefore(LocalDateTime.now().minusMinutes(9));
    }

    @Test
    void 重试上限可配置_放宽后会被重投() {
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenReturn(List.of(task("t3", TaskStatus.RUNNING, 2)));
        reaper(5).reap();

        verify(dispatcher).redispatch("t3", 7L);
        verify(stateService, never()).finish(any(), any(), any(), any());
    }

    @Test
    void 免鉴权模式下userId为空也能查到任务() {
        AnalysisTask t = task("t4", TaskStatus.PENDING, 0);
        t.setUserId(null);
        when(taskRepo.findByStatusInAndUpdatedAtBefore(anyList(), any())).thenReturn(List.of(t));
        reaper(2).reap();

        // userId 为 null 时 TaskDispatcher.loadTask 走 findByTaskId 分支，不按用户过滤
        verify(dispatcher, times(1)).redispatch("t4", null);
    }
}
