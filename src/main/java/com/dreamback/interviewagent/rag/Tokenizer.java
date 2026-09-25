package com.dreamback.interviewagent.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 检索用轻量分词器（中文 2-gram + 英文技术词）。
 *
 * <p><b>为什么不用 PostgreSQL 原生全文检索</b>：PG 内置配置（simple / english）不切分中文，
 * 整段中文会变成一个 token，检索退化成精确匹配。这里在 Java 侧预分词，
 * 再把结果交给 PG 的 {@code to_tsvector('simple', ?)} 存储与 {@code ts_rank} 排序。
 *
 * <p><b>为什么不用 pg_trgm</b>（另一种零依赖方案）：实测中文 trigram 对「同域细分主题」区分度不足 ——
 * 正例（MySQL 索引失效场景）0.3448 vs 同域难负例（MySQL 索引结构）0.2963，只差 1.16 倍；
 * 换成 2-gram + ts_rank 后是 0.0608 vs 0.0304，差 2.0 倍。而「同域细分主题互相干扰」
 * 恰好是本项目评测集里 bad case 的主要类型。
 *
 * <p>分词规则：
 * <ul>
 *   <li>ASCII 字母数字连续段 → 小写后保留（长度 ≥ 2，避免噪音），如 {@code mysql}、{@code jdk9}</li>
 *   <li>连续中文 → 2-gram 切分，如「索引失效」→ 索引、引失、失效；单字段保留原字</li>
 *   <li>其余字符（空格、标点、代码块符号）作为分隔符丢弃</li>
 * </ul>
 *
 * <p>产物只含 {@code [a-z0-9]} 与 CJK 字符，因此可直接拼进 {@code to_tsquery}，
 * 不会触发 tsquery 语法错误（{@code & | ! ( ) : *} 等特殊字符已在分词阶段被过滤）。
 */
@Component
public class Tokenizer {

    /** ASCII token 最小长度：单字符（如标点残留、单独字母）噪音大，丢弃 */
    private static final int ASCII_MIN_LEN = 2;

    /** 中文 n-gram 的 n */
    private static final int GRAM = 2;

    /** 分词结果：去重且保序（保序便于调试与复现） */
    public List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(ascii, seen);
                cjk.append(c);
            } else if (isAsciiWord(c)) {
                flushCjk(cjk, seen);
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(ascii, seen);
                flushCjk(cjk, seen);
            }
        }
        flushAscii(ascii, seen);
        flushCjk(cjk, seen);

        out.addAll(seen);
        return out;
    }

    /** 给 {@code to_tsvector('simple', ?)} 用：空格分隔的 token 串 */
    public String toVectorText(String text) {
        return String.join(" ", tokenize(text));
    }

    /**
     * 给 {@code to_tsquery('simple', ?)} 用：token 之间用 OR（{@code |}）连接。
     * 返回空串表示没有可用 token（调用方应据此跳过关键词通道）。
     */
    public String toTsQuery(String query) {
        List<String> tokens = tokenize(query);
        return tokens.isEmpty() ? "" : String.join(" | ", tokens);
    }

    private void flushAscii(StringBuilder sb, Set<String> out) {
        if (sb.length() >= ASCII_MIN_LEN) {
            out.add(sb.toString());
        }
        sb.setLength(0);
    }

    private void flushCjk(StringBuilder sb, Set<String> out) {
        int n = sb.length();
        if (n == 0) {
            return;
        }
        if (n < GRAM) {
            out.add(sb.toString());
        } else {
            for (int i = 0; i + GRAM <= n; i++) {
                out.add(sb.substring(i, i + GRAM));
            }
        }
        sb.setLength(0);
    }

    private static boolean isAsciiWord(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }
}
