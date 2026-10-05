package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.dto.MockAnswerRequest;
import com.dreamback.interviewagent.dto.MockAnswerResult;
import com.dreamback.interviewagent.dto.MockFinishResult;
import com.dreamback.interviewagent.dto.MockSessionDetail;
import com.dreamback.interviewagent.dto.MockStartRequest;
import com.dreamback.interviewagent.dto.MockStartResponse;
import com.dreamback.interviewagent.ratelimit.RateLimit;
import com.dreamback.interviewagent.service.MockInterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模拟面试（出题 / 作答打分 / 追问 / 汇总 / 回放）。 */
@RestController
@RequestMapping("/api/mock")
@RequiredArgsConstructor
public class MockController {

    private final MockInterviewService mockInterviewService;

    // 阈值全部走 app.ratelimit.rules（按方法名匹配），不再写死在注解上
    @PostMapping("/start")
    @RateLimit
    public MockStartResponse start(@Valid @RequestBody MockStartRequest req) {
        return mockInterviewService.start(req);
    }

    @PostMapping("/answer")
    @RateLimit
    public MockAnswerResult answer(@Valid @RequestBody MockAnswerRequest req) {
        return mockInterviewService.answer(req);
    }

    @PostMapping("/{sessionId}/finish")
    @RateLimit
    public MockFinishResult finish(@PathVariable Long sessionId) {
        return mockInterviewService.finish(sessionId);
    }

    @GetMapping("/{sessionId}")
    public MockSessionDetail detail(@PathVariable Long sessionId) {
        return mockInterviewService.detail(sessionId);
    }
}
