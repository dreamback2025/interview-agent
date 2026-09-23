package com.dreamback.interviewagent.config;

import com.dreamback.interviewagent.llm.DeepSeekLlmService;
import com.dreamback.interviewagent.llm.LlmService;
import com.dreamback.interviewagent.llm.StubLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
public class LlmConfig {

    @Bean
    public LlmService llmService(ChatClient.Builder chatClientBuilder,
                                 @Value("${spring.ai.openai.api-key:}") String apiKey,
                                 @Value("${spring.ai.openai.chat.options.model:}") String model,
                                 @Value("${app.llm.stub:false}") boolean forceStub) {
        boolean hasKey = StringUtils.hasText(apiKey) && !apiKey.startsWith("sk-placeholder");
        if (forceStub || !hasKey) {
            // 注意：环境变量被设置成空字符串时，Spring 不会回退到默认占位符，同样会走到这里
            log.warn("LLM 模式 = stub（原因：{}）。除 LLM 外的链路可用；" +
                            "配置 DEEPSEEK_API_KEY 或写入 application.yml 后【重启服务】才会生效。",
                    forceStub ? "app.llm.stub=true" : "未检测到可用 Key（为空或仍是占位符）");
            return new StubLlmService();
        }
        log.info("LLM 模式 = deepseek（model={}）", model);
        return new DeepSeekLlmService(chatClientBuilder.build());
    }
}
