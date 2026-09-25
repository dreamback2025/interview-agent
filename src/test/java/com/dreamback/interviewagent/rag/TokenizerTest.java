package com.dreamback.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    private final Tokenizer tokenizer = new Tokenizer();

    @Test
    void 中文按2gram切分() {
        assertThat(tokenizer.tokenize("索引失效")).containsExactly("索引", "引失", "失效");
    }

    @Test
    void 中文单字保留原字() {
        assertThat(tokenizer.tokenize("锁")).containsExactly("锁");
    }

    @Test
    void 英文转小写且长度大于等于2才保留() {
        // 单独的 "a" 是噪音（可能是代码符号残留），丢弃
        assertThat(tokenizer.tokenize("MySQL a idx")).containsExactly("mysql", "idx");
    }

    @Test
    void 中英混合各自处理() {
        List<String> tokens = tokenizer.tokenize("MySQL 索引失效");
        assertThat(tokens).containsExactly("mysql", "索引", "引失", "失效");
    }

    @Test
    void 标点与代码块符号作为分隔符丢弃() {
        // 「G1（回收）流程」：G1 是 ASCII token，中文被全角括号分隔成两段
        List<String> tokens = tokenizer.tokenize("G1（回收）流程");
        assertThat(tokens).containsExactly("g1", "回收", "流程");
    }

    @Test
    void tsquery特殊字符不会进入token() {
        // & | ! ( ) : * 是 tsquery 语法字符，若混进 token 会导致 to_tsquery 解析失败
        List<String> tokens = tokenizer.tokenize("a & b | c ! (d) e: f*");
        for (String t : tokens) {
            assertThat(t).doesNotContain("&", "|", "!", "(", ")", ":", "*");
        }
    }

    @Test
    void toTsQuery用OR连接() {
        assertThat(tokenizer.toTsQuery("索引失效")).isEqualTo("索引 | 引失 | 失效");
    }

    @Test
    void 空输入返回空() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThat(tokenizer.tokenize("   \n\t ")).isEmpty();
        assertThat(tokenizer.toTsQuery("")).isEmpty();
    }

    @Test
    void toVectorText为空格分隔且去重保序() {
        // 重复 token 只保留一次（保序：第一次出现的位置）
        assertThat(tokenizer.toVectorText("索引 索引 失效")).isEqualTo("索引 失效");
    }
}
