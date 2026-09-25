package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.MockAnswerRequest;
import com.dreamback.interviewagent.dto.MockAnswerResult;
import com.dreamback.interviewagent.dto.MockFinishResult;
import com.dreamback.interviewagent.dto.MockSessionDetail;
import com.dreamback.interviewagent.dto.MockStartRequest;
import com.dreamback.interviewagent.dto.MockStartResponse;

/**
 * 模拟面试服务：出题 → 逐题作答 → LLM 打分 + 追问 → 结束汇总，全程落库。
 *
 * <p>实现见 {@link com.dreamback.interviewagent.service.impl.MockInterviewServiceImpl}。
 * 同题最多追问两次；答得深 followUp 留空表示本题结束。
 */
public interface MockInterviewService {

    /** 按 JD 出题并建会话 */
    MockStartResponse start(MockStartRequest req);

    /** 作答 → LLM 打分 + 决定是否追问 */
    MockAnswerResult answer(MockAnswerRequest req);

    /** 结束并生成总评（总分 / 优点 / 改进） */
    MockFinishResult finish(Long sessionId);

    /** 整场回放 */
    MockSessionDetail detail(Long sessionId);
}
