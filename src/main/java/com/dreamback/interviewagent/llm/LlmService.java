package com.dreamback.interviewagent.llm;

import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * LLM 能力抽象。MVP 阶段只有两个实现：
 * - DeepSeekLlmService：真实调用
 * - StubLlmService：离线兜底（未配置 Key 或显式开启）
 */
public interface LlmService {

    /** 当前模式：deepseek / stub */
    String mode();

    /** 普通对话（用于 /chat 冒烟） */
    String chat(String userPrompt);

    /**
     * 结构化输出：返回 type 对应的对象。
     *
     * @param stubData 仅在 stub 模式下使用的数据源（保证离线也能验证链路）
     */
    <T> T structured(String systemPrompt, String userPrompt, Class<T> type, Supplier<T> stubData);

    /** 流式输出（SSE 逐段返回），用于页面实时渲染 */
    Flux<String> stream(String systemPrompt, String userPrompt);
}
