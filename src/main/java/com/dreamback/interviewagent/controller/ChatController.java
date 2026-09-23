package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.llm.LlmService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模型联通性冒烟接口 + 当前模式查看。 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final LlmService llmService;

    @GetMapping("/chat")
    public String chat(@RequestParam String q) {
        return llmService.chat(q);
    }

    /**
     * 注意：必须返回 JSON 对象。直接返回 String 时 Spring 会输出 text/plain 的裸文本，
     * 前端 JSON.parse 会失败（曾经导致页面永远显示 stub 模式）。
     */
    @GetMapping("/api/llm/mode")
    public Map<String, String> mode() {
        return Map.of("mode", llmService.mode());
    }
}
