package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.agent.InterviewTools;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 直接调用工具的自检端点：不经过模型，用来单独验证每个工具是否正常。 */
@RestController
@RequestMapping("/api/agent/tools")
@RequiredArgsConstructor
public class AgentController {

    private final InterviewTools tools;

    @GetMapping("/knowledge")
    public List<String> knowledge(@RequestParam String q) {
        return tools.searchKnowledgeBase(q);
    }

    @GetMapping("/weak-points")
    public List<String> weakPoints(@RequestParam String topic) {
        return tools.searchWeakPoints(topic);
    }

    @GetMapping("/jd")
    public List<String> jd(@RequestParam String jd) {
        return tools.getJdRequirements(jd);
    }
}
