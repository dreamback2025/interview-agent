package com.dreamback.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 切分策略的回归测试。
 *
 * 这套断言的作用：以后有人调 MAX_CHARS / 切分顺序时，能立刻发现「一个 chunk 混了多个主题」的退化
 * —— 那正是实测中出现过、且会直接拉低检索质量的问题。
 */
class TextSplitterTest {

    private final TextSplitter splitter = new TextSplitter();

    @Test
    @DisplayName("空输入与纯空白返回空列表")
    void blankInputReturnsEmpty() {
        assertThat(splitter.split(null)).isEmpty();
        assertThat(splitter.split("   \n\t\n ")).isEmpty();
    }

    @Test
    @DisplayName("按 Markdown 标题切分：一个小节一个 chunk")
    void splitsByHeading() {
        String note = """
                ## G1 垃圾收集器
                G1 把堆划分为大小相等的 Region。

                ## Redis 缓存三兄弟
                穿透用布隆过滤器，击穿用互斥锁。

                ## MySQL 索引失效
                最左前缀原则、隐式类型转换。
                """;

        List<String> chunks = splitter.split(note);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).startsWith("## G1 垃圾收集器");
        assertThat(chunks.get(1)).startsWith("## Redis 缓存三兄弟");
        assertThat(chunks.get(2)).startsWith("## MySQL 索引失效");
    }

    @Test
    @DisplayName("代码块内的 # 不算标题，不切分")
    void headingInsideCodeBlockIsIgnored() {
        String note = """
                ## 示例小节
                ```bash
                # 这是 shell 注释，不是 Markdown 标题
                echo hello
                ```

                正文结束。
                """;

        assertThat(splitter.split(note)).hasSize(1);
    }

    @Test
    @DisplayName("超长小节继续拆分，且首块保留标题（保留主题上下文）")
    void longSectionIsSplitWithHeadingKept() {
        String body = "这是一段用于测试切分的正文内容。".repeat(60);
        List<String> chunks = splitter.split("## 长小节\n" + body);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).startsWith("## 长小节");
        assertThat(chunks.get(1)).doesNotStartWith("##");
    }

    @Test
    @DisplayName("段落合并受 600 字上限约束，超出即分块")
    void respectsMaxCharsPerChunk() {
        String note = "## 标题\n" + "a".repeat(300) + "\n\n" + "b".repeat(300);

        List<String> chunks = splitter.split(note);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(608));
    }
}
