package com.dreamback.interviewagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.converter.BeanOutputConverter;
import reactor.core.publisher.Flux;

/**
 * 离线兜底：不调用任何外部 API。
 * 用调用方提供的 stubData 走一遍「序列化 -> BeanOutputConverter 反序列化」，
 * 保证除 LLM 之外的链路（Prompt 拼装、JSON 解析、落库）都能被真实验证。
 */
public class StubLlmService implements LlmService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String mode() {
        return "stub";
    }

    @Override
    public String chat(String userPrompt) {
        return "[stub 模式] 未配置 DEEPSEEK_API_KEY，未真实调用模型。收到的问题：" + userPrompt;
    }

    @Override
    public <T> T structured(String systemPrompt, String userPrompt, Class<T> type, Supplier<T> stubData) {
        try {
            String json = objectMapper.writeValueAsString(stubData.get());
            return new BeanOutputConverter<>(type).convert(json);
        } catch (Exception e) {
            throw new IllegalStateException("stub 模式结构化输出失败: " + e.getMessage(), e);
        }
    }

    /** 离线流式：把一段固定文本按小块吐出去，用来验证 SSE 链路（含真实的分段渲染） */
    @Override
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        String text = "# 复盘报告（stub 模式）\n\n"
                + "未配置 DEEPSEEK_API_KEY，这是离线占位内容，仅用于验证流式输出链路是否正常。\n\n"
                + "## 总评\n\n收到的问题长度 " + (userPrompt == null ? 0 : userPrompt.length()) + " 字。\n\n"
                + "## 下次优先补强\n\n1. 配置真实 Key 后重新分析\n";
        return Flux.fromIterable(chunk(text, 8))
                .delayElements(Duration.ofMillis(60));
    }

    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return out;
    }
}
