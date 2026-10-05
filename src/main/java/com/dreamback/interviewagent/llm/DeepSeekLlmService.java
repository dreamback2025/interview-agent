package com.dreamback.interviewagent.llm;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import org.springframework.web.server.ResponseStatusException;

/** 真实调用 DeepSeek（OpenAI 协议兼容）。 */
public class DeepSeekLlmService implements LlmService {

    private final ChatClient chatClient;
    private final long timeoutMs;
    private final ExecutorService timeoutExecutor;

    public DeepSeekLlmService(ChatClient chatClient, long timeoutMs) {
        this.chatClient = chatClient;
        this.timeoutMs = timeoutMs;
        // 每个调用一个虚拟线程：只为「能超时取消等待」而存在，不占平台线程，也无需池化。
        // 虚拟线程是 daemon，随 JVM 退出，不必显式 shutdown。
        this.timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public String mode() {
        return "deepseek";
    }

    @Override
    public String chat(String userPrompt) {
        return withTimeout(() -> {
            String content = chatClient.prompt().user(userPrompt).call().content();
            return content == null ? "" : content;
        });
    }

    @Override
    public <T> T structured(String systemPrompt, String userPrompt, Class<T> type, Supplier<T> stubData) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String sys = systemPrompt
                + "\n\n【输出要求】只输出一个纯 JSON 对象，不要用 ``` 代码块包裹，不要输出任何解释文字。"
                + "必须严格遵循下面的 JSON schema：\n"
                + converter.getFormat();
        String raw = withTimeout(() -> chatClient.prompt().system(sys).user(userPrompt).call().content());
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

    /**
     * 给同步调用套一个上限时间。
     *
     * <p>为什么必须有：Spring AI 的同步调用默认没有读超时，上游一旦挂起（网络黑洞、
     * 对端不响应），线程与连接会永久占用 —— 这是唯一能把系统从「慢」拖到「死」的路径。
     * 有了这个上限，最坏情况是占用 timeoutMs 后返回 504，资源归还。
     *
     * <p>局限（如实说明）：超时只是「不再等」，底层 socket 读仍在虚拟线程里继续，
     * 直到上游返回或 TCP 层超时。真正断开需要客户端主动 cancel，同步接口做不到。
     */
    private <T> T withTimeout(Supplier<T> call) {
        if (timeoutMs <= 0) {
            return call.get();
        }
        try {
            return CompletableFuture.supplyAsync(call::get, timeoutExecutor)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "模型调用超时（" + timeoutMs + "ms），请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "模型调用被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ResponseStatusException rse) {
                throw rse;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }
}
