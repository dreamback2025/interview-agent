package com.dreamback.interviewagent.entity;

/** 分析任务状态机：PENDING → RUNNING → SUCCESS / FAILED */
public enum TaskStatus {

    /** 已创建，等待调度（可能还在消息队列里） */
    PENDING,

    /** 消费端已开始执行（正在调用模型） */
    RUNNING,

    /** 执行成功，报告已落库 */
    SUCCESS,

    /** 执行失败（含重试次数用尽） */
    FAILED
}
