package com.dreamback.interviewagent.llm;

import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import reactor.core.publisher.Flux;

/** 真实调用 DeepSeek（OpenAI 协议兼容）。 */
public class DeepSeekLlmService implements LlmService {

    private final ChatClient chatClient;

    public DeepSeekLlmService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String mode() {
        return "deepseek";
    }

    @Override
    public String chat(String userPrompt) {
        String content = chatClient.prompt().user(userPrompt).call().content();
        return content == null ? "" : content;
    }

    @Override
    public <T> T structured(String systemPrompt, String userPrompt, Class<T> type, Supplier<T> stubData) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String sys = systemPrompt
                + "\n\n【输出要求】只输出一个纯 JSON 对象，不要用 ``` 代码块包裹，不要输出任何解释文字。"
                + "必须严格遵循下面的 JSON schema：\n"
                + converter.getFormat();
        String raw = chatClient.prompt().system(sys).user(userPrompt).call().content();
        return converter.convert(JsonUtil.cleanJson(raw));
    }

    @Override
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content();
    }
}
