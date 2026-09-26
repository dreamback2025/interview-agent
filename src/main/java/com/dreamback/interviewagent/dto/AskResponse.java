package com.dreamback.interviewagent.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 带相关性判定的检索响应（`/api/knowledge/ask`）。
 *
 * <p>与 {@code /search} 的区别：search 只回答「哪些片段最像」，
 * ask 还要回答「这些片段够不够回答问题」。
 */
@Data
public class AskResponse {

    /**
     * 检索结果。confident=false 时**仍然返回** ——
     * 由调用方决定是拒答还是展示为「可能相关，仅供参考」。
     */
    private List<SearchResult> results = new ArrayList<>();

    /** 是否有把握。false = 语料里很可能没有相关内容，应当拒答而不是硬答。 */
    private boolean confident = true;

    /** 判定说明（confident=false 时给出具体原因） */
    private String reason;

    /** 内部信号，用于调参与线上排查 */
    private Signals signals = new Signals();

    @Data
    public static class Signals {
        /** 向量通道 top1 的余弦相似度 */
        private double vecTop1;
        /** 关键词通道命中数（**注意**：OR 查询下通用词会污染它，仅作诊断，不参与判定） */
        private int kwHits;
        /**
         * 关键词覆盖率：查询分词后，有多少比例的 token 出现在 top1 文档里（0~1）。
         *
         * <p>这才是有效的关键词信号 —— kwHits 回答「有几个文档沾边」，
         * coverage 回答「有没有一个文档真正覆盖了这个问题」。
         */
        private double kwCoverage;
        /** 融合后 top1 的 RRF 分 */
        private double rrfTop1;
    }
}
