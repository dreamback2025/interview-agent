package com.dreamback.interviewagent.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 检索结果 + 内部信号。
 *
 * <p><b>不是对外端点的响应体</b> —— 对外的检索入口是 {@code /api/knowledge/search}（只返回结果列表），
 * 用于「查看/验证自己的笔记」。
 *
 * <p>这里的 {@code confident} 与 {@code signals} 服务于**分析流程**：分析一道答错的题时，
 * 需要知道「召回的内容靠不靠谱」，才能判断这道题是
 * 「笔记里写了、但你没答出来」（该重新消化笔记）还是「笔记里压根没写」（该补一篇）。
 *
 * <p>换句话说：这套信号是给「判断笔记覆盖度」用的，不是给「系统替用户回答问题」用的 ——
 * 本产品里系统从不替用户作答。
 */
@Data
public class RetrievalResult {

    private List<SearchResult> results = new ArrayList<>();

    /**
     * 检索是否达到「确实覆盖了这个问题」的判定线。
     *
     * <p>在分析流程里：false = 用户笔记里没有这个知识点（知识缺口）；
     * true = 笔记里有，只是这次没答出来。
     */
    private boolean confident = true;

    /** 判定说明（confident=false 时给出原因） */
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
