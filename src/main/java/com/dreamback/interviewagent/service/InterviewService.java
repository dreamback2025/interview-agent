package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.CreateInterviewRequest;
import com.dreamback.interviewagent.dto.InterviewDetailResponse;
import com.dreamback.interviewagent.dto.InterviewSummaryResponse;
import com.dreamback.interviewagent.entity.InterviewRecord;
import java.util.List;

/**
 * 面试记录 CRUD 服务。
 *
 * <p>实现见 {@link com.dreamback.interviewagent.service.impl.InterviewServiceImpl}。
 * 拆接口的目的：固定对外契约，便于切实现（如换成基于 ES 的检索式列表）和单测 mock。
 */
public interface InterviewService {

    /** 录入一次面试（含若干题目），返回详情视图 */
    InterviewDetailResponse create(CreateInterviewRequest req);

    /** 当前用户可见的面试记录列表（按时间倒序） */
    List<InterviewSummaryResponse> list();

    /** 面试详情（含题目）。越权访问返回 404，不泄露存在性 */
    InterviewDetailResponse get(Long id);

    /** 实体 -> 详情 DTO；导出与其他服务复用 */
    InterviewDetailResponse toDetail(InterviewRecord r);
}
