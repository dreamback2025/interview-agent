package com.dreamback.interviewagent.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JD 提炼的配置项。
 *
 * <p>把原来硬编码在 {@code InterviewTools} 里的技术关键词搬到这里 ——
 * 改一个词不需要动代码、不需要重新编译。
 *
 * <p>关键词有两个来源，最终取并集：
 * <ol>
 *   <li><b>配置</b>（本类的 keywords）：通用兜底词，也可以手工补充；</li>
 *   <li><b>动态</b>（知识库标签）：用户自己导入的笔记标签就是他的技术栈，
 *       面 Java 的人得到 Java 系的词，面 Go 的人得到 Go 系的词，天然个性化。</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.jd")
public class JdProperties {

    /**
     * JD 提炼用的技术关键词。
     *
     * <p>留空也不会失效 —— 会退化为「知识库标签」这个动态来源；两者都为空时才走
     * 「按标点切分职责短语」的兜底逻辑。
     */
    private List<String> keywords = new ArrayList<>();

    /**
     * 是否启用「知识库标签」作为动态关键词来源。
     *
     * <p>关掉后只用配置里的词（适合笔记标签很杂、噪音大的场景）。
     */
    private boolean dynamicTags = true;

    /** 提炼结果最多返回多少条（防止 JD 很长时输出爆炸） */
    private int maxRequirements = 12;

    /**
     * 动态关键词的缓存时间（毫秒）。**默认 0 = 不缓存**。
     *
     * <p>为什么不默认缓存：缓存就要处理失效 —— 用户刚补了一篇新领域的笔记，
     * 却要等几十秒才能被 JD 提炼用上，这个体验说不过去。而 {@code docs()} 只是一次轻量查询，
     * JD 提炼本身也不是高频操作，为它引入一套缓存失效机制不划算。
     * 真到高频场景再调大这个值（并配合失效调用）。
     */
    private long keywordsCacheMs = 0L;
}
