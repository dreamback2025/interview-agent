package com.dreamback.interviewagent.ratelimit;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流规则配置（{@code app.ratelimit.*}）。
 *
 * <p>把阈值从注解里挪出来，是为了让「调限流」不需要改代码发版 ——
 * 也为了让不同环境能用不同值：本地自用例宽一点，公网部署收紧。
 *
 * <pre>
 * app:
 *   ratelimit:
 *     defaults: {qpm: 60, max-in-flight: 2, global-qpm: 0, global-in-flight: 32}
 *     rules:
 *       InterviewController.analyze: {qpm: 20, max-in-flight: 2}
 * </pre>
 *
 * <p>匹配键是「类短名.方法名」，与 {@link RateLimitAspect} 拼出来的方法标识一致；
 * 匹配不到就回落 defaults。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ratelimit")
public class RateLimitProperties {

    /** 是否启用限流切面（Redis 不可用时各维度会自动降级放行） */
    private boolean enabled = true;

    /** 兜底规则 */
    private Rule defaults = new Rule(60, 2, 0, 32);

    /** 按方法名定制的规则 */
    private Map<String, Rule> rules = new LinkedHashMap<>();

    /** 查规则：先按方法名精确匹配，没有就用 defaults */
    public Rule ruleFor(String method) {
        Rule r = rules.get(method);
        return r == null ? defaults : r;
    }

    /** 一条限流规则的四个维度，0 表示该维度不限 */
    @Data
    public static class Rule {
        private int qpm;
        private int maxInFlight;
        private int globalQpm;
        private int globalInFlight;

        public Rule() {
        }

        public Rule(int qpm, int maxInFlight, int globalQpm, int globalInFlight) {
            this.qpm = qpm;
            this.maxInFlight = maxInFlight;
            this.globalQpm = globalQpm;
            this.globalInFlight = globalInFlight;
        }
    }
}
